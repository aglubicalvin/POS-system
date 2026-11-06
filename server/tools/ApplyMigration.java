import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import com.businessstore.pos.tools.MigrationConfig;

/**
 * Apply ONE migration file to an explicitly confirmed database, transactionally.
 *
 * Safety contract, enforced in code:
 *  1. No default target. The previous version fell back to a hard-coded Supabase
 *     pooler URL when CHECK_URL was unset, so a typo could apply migrations to a
 *     shared project. Configuration is now validated by MigrationConfig BEFORE a
 *     connection exists, and an unset URL is a hard failure.
 *  2. Explicit confirmation (MIGRATION_CONFIRM=yes) and an independently supplied
 *     expected project reference (MIGRATION_EXPECTED_TARGET) are required.
 *  3. The migration file, the supabase_migrations tracking structures it needs
 *     and its history row are written in ONE transaction: if any step fails,
 *     none of them is kept. A migration can therefore never be recorded as
 *     applied after partial execution, and a database that has never held a
 *     migration is bootstrapped atomically instead of assuming the tracking
 *     schema already exists.
 *  4. Diagnostics are sanitized: SQLState and a short message with host, port,
 *     database and role scrubbed. No stack traces, no credentials.
 *
 * Usage:
 *   MIGRATION_CONFIRM=yes MIGRATION_EXPECTED_TARGET=<ref> MIGRATION_FILE=<path>
 *   CHECK_URL=jdbc:postgresql://host:port/postgres CHECK_USER=... CHECK_PASS=...
 *   java -cp ... com.businessstore.pos.tools.ApplyMigration
 *
 * Exit codes: 0 applied; 2 configuration rejected; 3 connected target is not the
 * expected one; 4 migration failed and was rolled back.
 */
public class ApplyMigration {

    public static void main(String[] args) {
        MigrationConfig.Config config;
        try {
            config = MigrationConfig.resolve(System.getenv());
        } catch (MigrationConfig.Rejected rejected) {
            System.err.println("MIGRATION_REJECTED code=" + rejected.code);
            System.err.println("  " + rejected.getMessage());
            System.exit(2);
            return;
        }

        String sql;
        try {
            sql = Files.readString(Path.of(config.migrationFile));
        } catch (java.io.IOException e) {
            System.err.println("MIGRATION_REJECTED code=unreadable-migration-file");
            System.exit(2);
            return;
        }

        System.out.println("== apply migration ==");
        System.out.println("  target: " + config.describe());
        System.out.println("  file: " + Path.of(config.migrationFile).getFileName());

        try (Connection c = DriverManager.getConnection(config.url, config.user, config.password)) {
            // Verify the CONNECTED session before writing anything.
            String actualUser;
            String actualDatabase;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("select current_user, current_database()")) {
                if (!rs.next()) {
                    System.err.println("MIGRATION_REJECTED code=identity-unavailable");
                    System.exit(3);
                    return;
                }
                actualUser = rs.getString(1);
                actualDatabase = rs.getString(2);
            }
            if (!"postgres".equals(actualDatabase)) {
                System.err.println("MIGRATION_REJECTED code=unexpected-database");
                System.exit(3);
                return;
            }
            // Identity contract, aligned with Provision.identityProblem and with
            // SETUP.md: a shared pooler does not necessarily expose the original
            // login in current_user, so an UNSUFFIXED role is legitimate and is
            // not a failure. What must never pass is a role naming a DIFFERENT
            // project than MIGRATION_EXPECTED_TARGET.
            //
            // The project reference is still enforced: it was compared against
            // CHECK_USER during configuration validation, and here the connected
            // role is compared against the same expected reference.
            java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("^postgres\\.([a-z0-9]{20})$").matcher(actualUser);
            if (m.matches() && !m.group(1).equals(config.expectedTarget)) {
                System.err.println("MIGRATION_REJECTED code=target-mismatch");
                System.exit(3);
                return;
            }

            // ONE transaction for the bootstrap DDL, the migration SQL and its
            // history row. A database that has never been migrated has no
            // supabase_migrations schema, so the tracking structures are created
            // here rather than assumed: without this the very first migration
            // would fail on the history insert after its own DDL had already run.
            // CREATE SCHEMA and CREATE TABLE are transactional in PostgreSQL, so a
            // failure removes the tracking structures together with the migration.
            c.setAutoCommit(false);
            try {
                try (Statement st = c.createStatement()) {
                    st.execute("create schema if not exists supabase_migrations");
                    st.execute(
                            "create table if not exists supabase_migrations.schema_migrations (" +
                            " version text primary key," +
                            " statements jsonb," +
                            " name text not null," +
                            " statements_hash text," +
                            " executed_at timestamp not null default clock_timestamp())");
                    st.execute(sql);
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "insert into supabase_migrations.schema_migrations (version, name, statements) " +
                        "values (?, ?, to_jsonb(?::text)) on conflict (version) do nothing")) {
                    ps.setString(1, versionOf(config.migrationFile));
                    ps.setString(2, Path.of(config.migrationFile).getFileName().toString());
                    ps.setString(3, "applied by ApplyMigration");
                    ps.executeUpdate();
                }
                c.commit();
                System.out.println("  migration_status=applied");
                System.out.println("  version=" + versionOf(config.migrationFile));
            } catch (SQLException e) {
                try {
                    c.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                System.err.println("  migration_status=rolled_back");
                System.err.println("  error_detail=" + sanitize(e));
                System.exit(4);
            }
        } catch (SQLException e) {
            System.err.println("  migration_status=error");
            System.err.println("  error_category=connection_failed");
            System.err.println("  error_detail=" + sanitize(e));
            System.exit(4);
        }
    }

    /** "0003_admin_roles.sql" -> "0003". */
    static String versionOf(String file) {
        String name = Path.of(file).getFileName().toString();
        int underscore = name.indexOf('_');
        return underscore > 0 ? name.substring(0, underscore) : name.replace(".sql", "");
    }

    /**
     * SQLState plus a short message with host, port, database and role scrubbed.
     * Never the raw driver text, which can carry connection details.
     */
    static String sanitize(SQLException e) {
        String state = e.getSQLState() == null ? "unknown" : e.getSQLState();
        String msg = e.getMessage() == null ? "" : e.getMessage();
        msg = msg.replaceAll("[\\w.-]+\\.(supabase\\.com|com|net|org)", "<host>");
        msg = msg.replaceAll("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?", "<host>");
        msg = msg.replaceAll("\\b(postgres|authenticator)\\.[a-z0-9]{20}\\b", "<role>");
        msg = msg.replaceAll("\\s+", " ").trim();
        if (msg.length() > 120) msg = msg.substring(0, 120) + "...";
        return "sqlstate=" + state + " message=" + msg;
    }
}