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
 * Usage: CHECK_URL=jdbc:postgresql://<host>:<port>/<db> CHECK_USER=... CHECK_PASS=...
 */
public class CheckSchema {
    public static void main(String[] args) throws Exception {
        // All configuration is validated BEFORE a connection exists: an invalid
        // configuration must never reach the network. See com.businessstore.pos.tools.CheckSchemaConfig.
        com.businessstore.pos.tools.CheckSchemaConfig.Config config;
        try {
            config = com.businessstore.pos.tools.CheckSchemaConfig.resolve(System.getenv());
        } catch (com.businessstore.pos.tools.CheckSchemaConfig.Rejected rejected) {
            System.err.println("CHECK_REJECTED code=" + rejected.code);
            System.err.println("  " + rejected.getMessage());
            System.exit(2);
            return;
        }

        try (Connection c = DriverManager.getConnection(config.url, config.user, config.password)) {
            c.setAutoCommit(false);
            c.setReadOnly(true);

            // Verify the CONNECTED session, not merely the strings we supplied.
            String actualUser;
            String actualDatabase;
            try (Statement st = c.createStatement();
                 ResultSet id = st.executeQuery("select current_user, current_database()")) {
                if (!id.next()) {
                    System.err.println("CHECK_REJECTED code=identity-unavailable");
                    System.exit(3);
                    return;
                }
                actualUser = id.getString(1);
                actualDatabase = id.getString(2);
            }
            try {
                com.businessstore.pos.tools.CheckSchemaConfig.verifyIdentity(config, actualUser, actualDatabase);
            } catch (com.businessstore.pos.tools.CheckSchemaConfig.Rejected rejected) {
                System.err.println("CHECK_REJECTED code=" + rejected.code);
                System.err.println("  " + rejected.getMessage());
                System.exit(3);
                return;
            }

            ResultSet rs = c.createStatement().executeQuery(
                "select table_name from information_schema.tables " +
                "where table_schema='public' and table_type='BASE TABLE' order by 1");
            var names = new java.util.ArrayList<String>();
            while (rs.next()) names.add(rs.getString(1));
            System.out.println("base tables in public schema (" + names.size() + "):");
            for (String n : names) System.out.println("  " + n);

            String[] expected = {"devices","device_tokens","users","suppliers","products",
                "goods_received","goods_received_items","shifts","sales","sale_items","payments",
                "stock_movements","stock_on_hand","receipt_counters","outbox","processed_events",
                "approvals","audit_log","device_sync_state","pending_events","rejected_events"};
            var missing = new java.util.ArrayList<String>();
            for (String e : expected) if (!names.contains(e)) missing.add(e);
            System.out.println("missing canonical tables: " + (missing.isEmpty() ? "NONE - all present" : missing));

            rs = c.createStatement().executeQuery(
                "select table_name from information_schema.views where table_schema='public' order by 1");
            var views = new java.util.ArrayList<String>();
            while (rs.next()) views.add(rs.getString(1));
            System.out.println("views: " + (views.isEmpty() ? "none" : views));

            rs = c.createStatement().executeQuery(
                "select relname, relrowsecurity from pg_class c join pg_namespace n on n.oid=c.relnamespace " +
                "where n.nspname='public' and c.relkind='r' order by 1");
            var rls = new java.util.ArrayList<String>();
            while (rs.next()) if (rs.getBoolean(2)) rls.add(rs.getString(1));
            System.out.println("tables with RLS enabled: " + (rls.isEmpty() ? "none" : rls));

            rs = c.createStatement().executeQuery(
                "select routine_name from information_schema.routines " +
                "where routine_schema='public' order by 1");
            var fns = new java.util.ArrayList<String>();
            while (rs.next()) fns.add(rs.getString(1));
            System.out.println("functions in public: " + (fns.isEmpty() ? "none" : fns));

            c.rollback();
        }
    }
}