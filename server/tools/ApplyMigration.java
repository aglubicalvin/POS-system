import java.nio.file.*;
import java.sql.*;

/**
 * Applies one migration file inside a single transaction, then records it in
 * supabase_migrations.schema_migrations (same bookkeeping the Supabase CLI uses).
 *
 * Usage: env CHECK_URL/CHECK_USER/CHECK_PASS, args: <sql-file> <version> <name>
 */
public class ApplyMigration {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: ApplyMigration <sql-file> <version> <name>");
            System.exit(2);
        }
        String file = args[0], version = args[1], name = args[2];
        String sql = Files.readString(Path.of(file));

        String url = System.getenv().getOrDefault("CHECK_URL",
            "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres");
        String user = System.getenv("CHECK_USER");
        String pass = System.getenv("CHECK_PASS");

        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            c.setAutoCommit(false);
            try {
                try (Statement st = c.createStatement()) {
                    st.execute(sql);
                }
                try (Statement st = c.createStatement()) {
                    st.execute(
                        "create schema if not exists supabase_migrations");
                    st.execute(
                        "create table if not exists supabase_migrations.schema_migrations (" +
                        " version text primary key," +
                        " statements jsonb," +
                        " name text not null," +
                        " statements_hash text," +
                        " executed_at timestamp not null default clock_timestamp())");
                    try (PreparedStatement ps = c.prepareStatement(
                        "insert into supabase_migrations.schema_migrations (version, name, statements)" +
                        " values (?, ?, to_jsonb(?::text))" +
                        " on conflict (version) do nothing")) {
                        ps.setString(1, version);
                        ps.setString(2, name);
                        ps.setString(3, "applied via JDBC by ApplyMigration.java");
                        ps.executeUpdate();
                    }
                }
                c.commit();
                System.out.println("APPLIED AND COMMITTED: version " + version + " (" + name + ") from " + file);
            } catch (Throwable t) {
                c.rollback();
                System.out.println("ROLLED BACK — nothing was applied. Error:");
                t.printStackTrace();
                System.exit(1);
            }
        }
    }
}
