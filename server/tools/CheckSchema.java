import java.sql.*;

/**
 * READ-ONLY schema inventory check for an explicitly nominated database.
 *
 * There is deliberately NO default target: this tool used to fall back to a
 * hard-coded Supabase pooler URL when CHECK_URL was unset, so a typo in the
 * environment silently pointed a developer at a shared project. It now refuses
 * instead, exactly like ProbeState.
 *
 * Every statement below is a SELECT against information_schema / pg_class.
 * This tool never writes.
 *
 * Failures are sanitized. A pgJDBC exception message contains the host and port
 * it dialled, and an uncaught exception would let the JVM print that message and
 * a stack trace to stderr. Neither is acceptable for a tool whose input can be a
 * shared production project, so only a category and the SQLState are printed:
 *
 *   CHECK_FAILED code=connection_failed sqlstate=08001
 *   CHECK_FAILED code=query_failed sqlstate=42501
 *
 * Usage: CHECK_URL=jdbc:postgresql://<host>:<port>/<db> CHECK_USER=... CHECK_PASS=...
 */
public class CheckSchema {

    /** Configuration refused before any connection exists (existing contract). */
    static final int EXIT_REFUSED = 2;
    /** Connected, but the session is not the expected identity. */
    static final int EXIT_IDENTITY = 3;
    /** The connection could not be established. */
    static final int EXIT_CONNECTION = 4;
    /** The connection worked, a read-only query was rejected. */
    static final int EXIT_QUERY = 5;
    /** Anything not anticipated (defensive: still sanitized). */
    static final int EXIT_UNEXPECTED = 6;

    public static void main(String[] args) {
        System.exit(run());
    }

    /** All work happens here so every resource is closed before the exit code. */
    static int run() {
        // All configuration is validated BEFORE a connection exists: an invalid
        // configuration must never reach the network. See com.businessstore.pos.tools.CheckSchemaConfig.
        com.businessstore.pos.tools.CheckSchemaConfig.Config config;
        try {
            config = com.businessstore.pos.tools.CheckSchemaConfig.resolve(System.getenv());
        } catch (com.businessstore.pos.tools.CheckSchemaConfig.Rejected rejected) {
            return refuse(rejected);
        }

        boolean connected = false;
        try (Connection c = DriverManager.getConnection(config.url, config.user, config.password)) {
            connected = true;
            c.setAutoCommit(false);
            c.setReadOnly(true);

            // Verify the CONNECTED session, not merely the strings we supplied.
            String actualUser;
            String actualDatabase;
            try (Statement st = c.createStatement();
                 ResultSet id = st.executeQuery("select current_user, current_database()")) {
                if (!id.next()) {
                    System.err.println("CHECK_REJECTED code=identity-unavailable");
                    System.err.println("  the session did not report a role or database.");
                    return EXIT_IDENTITY;
                }
                actualUser = id.getString(1);
                actualDatabase = id.getString(2);
            }
            try {
                com.businessstore.pos.tools.CheckSchemaConfig.verifyIdentity(config, actualUser, actualDatabase);
            } catch (com.businessstore.pos.tools.CheckSchemaConfig.Rejected rejected) {
                return refuse(rejected, EXIT_IDENTITY);
            }

            report(inventory(c));

            c.rollback();
            return 0;
        } catch (SQLException failure) {
            // Nothing from the driver's message is printed: it names the host, the
            // port and sometimes the URL. Only the category and SQLState leave.
            return failed(
                connected ? "query_failed" : "connection_failed",
                connected ? EXIT_QUERY : EXIT_CONNECTION,
                sqlStateOf(failure),
                connected
                    ? "  the connection succeeded but a read-only schema query was rejected; check the role's read permissions."
                    : "  the target could not be reached or did not accept the connection; check the host, port and TLS settings.");
        } catch (RuntimeException failure) {
            // Defensive: a driver or JDBC-layer failure that is not an SQLException
            // must not reach the default handler either.
            return failed("unexpected_failure", EXIT_UNEXPECTED, "unavailable",
                "  the check stopped unexpectedly; no connection details are reported.");
        }
    }

    /** The read-only inventory itself. Throws SQLException for the caller to sanitize. */
    private static Inventory inventory(Connection c) throws SQLException {
        var names = new java.util.ArrayList<String>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "select table_name from information_schema.tables " +
                 "where table_schema='public' and table_type='BASE TABLE' order by 1")) {
            while (rs.next()) names.add(rs.getString(1));
        }

        String[] expected = {"devices", "device_tokens", "users", "suppliers", "products",
            "goods_received", "goods_received_items", "shifts", "sales", "sale_items", "payments",
            "stock_movements", "stock_on_hand", "receipt_counters", "outbox", "processed_events",
            "approvals", "audit_log", "device_sync_state", "pending_events", "rejected_events"};
        var missing = new java.util.ArrayList<String>();
        for (String e : expected) if (!names.contains(e)) missing.add(e);

        var views = new java.util.ArrayList<String>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "select table_name from information_schema.views where table_schema='public' order by 1")) {
            while (rs.next()) views.add(rs.getString(1));
        }

        var rls = new java.util.ArrayList<String>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "select relname, relrowsecurity from pg_class c join pg_namespace n on n.oid=c.relnamespace " +
                 "where n.nspname='public' and c.relkind='r' order by 1")) {
            while (rs.next()) if (rs.getBoolean(2)) rls.add(rs.getString(1));
        }

        var fns = new java.util.ArrayList<String>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "select routine_name from information_schema.routines where routine_schema='public' order by 1")) {
            while (rs.next()) fns.add(rs.getString(1));
        }
        return new Inventory(names, missing, views, rls, fns);
    }

    private record Inventory(
        java.util.List<String> tables,
        java.util.List<String> missing,
        java.util.List<String> views,
        java.util.List<String> rlsTables,
        java.util.List<String> functions
    ) {}

    private static void report(Inventory inventory) {
        System.out.println("base tables in public schema (" + inventory.tables().size() + "):");
        for (String n : inventory.tables()) System.out.println("  " + n);
        System.out.println("missing canonical tables: " +
            (inventory.missing().isEmpty() ? "NONE - all present" : inventory.missing()));
        System.out.println("views: " + (inventory.views().isEmpty() ? "none" : inventory.views()));
        System.out.println("tables with RLS enabled: " +
            (inventory.rlsTables().isEmpty() ? "none" : inventory.rlsTables()));
        System.out.println("functions in public: " +
            (inventory.functions().isEmpty() ? "none" : inventory.functions()));
    }

    /** A configuration rejection: the message is already value-free by construction. */
    private static int refuse(com.businessstore.pos.tools.CheckSchemaConfig.Rejected rejected) {
        return refuse(rejected, EXIT_REFUSED);
    }

    private static int refuse(com.businessstore.pos.tools.CheckSchemaConfig.Rejected rejected, int exitCode) {
        System.err.println("CHECK_REJECTED code=" + rejected.code);
        System.err.println("  " + rejected.getMessage());
        return exitCode;
    }

    private static int failed(String code, int exitCode, String sqlState, String hint) {
        System.err.println("CHECK_FAILED code=" + code + " sqlstate=" + sqlState);
        System.err.println(hint);
        return exitCode;
    }

    /**
     * The SQLState of the failure, following the chain pgJDBC uses for wrapped
     * errors. A SQLState is a five-character code and can never carry a host,
     * port or credential, which is why it is the only detail that is safe to print.
     */
    static String sqlStateOf(SQLException failure) {
        SQLException current = failure;
        while (current != null) {
            String state = current.getSQLState();
            if (state != null && !state.isEmpty()) return state;
            current = current.getNextException();
        }
        return "unavailable";
    }
}
