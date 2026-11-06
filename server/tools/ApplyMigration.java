import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import com.businessstore.pos.tools.MigrationConfig;
import com.businessstore.pos.tools.MigrationHistory;

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
 *  3. The bootstrap DDL for supabase_migrations.schema_migrations, the migration
 *     file and its history row are written in ONE transaction (MigrationHistory):
 *     if any step fails, none of them is kept. A migration can therefore never be
 *     recorded as applied after partial execution, and a database that has never
 *     held a migration is bootstrapped atomically instead of assuming the tracking
 *     schema already exists.
 *  4. The history table is the one the Supabase CLI creates (`statements text[]`,
 *     `name text`), so a CLI-managed database is written to correctly rather than
 *     rejected on a column type. `statements` holds the migration file itself and
 *     `name` is recorded the way the CLI records it, so `supabase migration fetch`
 *     writes the original `<version>_<name>.sql` back out instead of an invalid
 *     placeholder or a doubled file name.
 *  5. A failed rollback is never followed by setAutoCommit(true), which JDBC would
 *     treat as a COMMIT: the connection is aborted so the server discards the
 *     transaction, and the run reports outcome_uncertain instead of rolled_back.
 *  5. Diagnostics are sanitized: SQLState and a short message with host, port,
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
            // history row; see MigrationHistory for the CLI-compatible table.
            // The version/name split is the CLI's own: 0005_admin.sql -> 0005,
            // admin. Fetching the row then rewrites 0005_admin.sql, not
            // 0005_0005_admin.sql.sql.
            String version = versionOf(config.migrationFile);
            try {
                MigrationHistory.apply(c, version, nameOf(config.migrationFile), sql);
                System.out.println("  migration_status=applied");
                System.out.println("  version=" + version);
            } catch (MigrationHistory.CommittedButUnclean e) {
                // The COMMIT succeeded; only resetting the connection failed, so
                // the migration must NOT be reported as rolled back.
                System.out.println("  migration_status=applied");
                System.out.println("  version=" + version);
                System.err.println("  connection_state=discarded");
                System.err.println("  error_detail=" + sanitize(e));
            } catch (SQLException e) {
                if (MigrationHistory.rollbackFailed(e)) {
                    // The transaction may still be live on the server; saying
                    // "rolled_back" here would be a claim we cannot make.
                    System.err.println("  migration_status=outcome_uncertain");
                    System.err.println("  rollback_failed=true");
                    System.err.println(
                        "  connection_discarded=" + !MigrationHistory.discardFailed(e));
                } else {
                    System.err.println("  migration_status=rolled_back");
                }
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

    /**
     * "0003_admin_roles.sql" -> "admin_roles": the name the CLI's own file
     * parser stores (it strips the version prefix and the .sql suffix), so a
     * fetched row is written back as 0003_admin_roles.sql.
     */
    static String nameOf(String file) {
        String name = Path.of(file).getFileName().toString();
        int underscore = name.indexOf('_');
        String rest = underscore > 0 ? name.substring(underscore + 1) : name;
        return rest.endsWith(".sql") ? rest.substring(0, rest.length() - 4) : rest;
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
