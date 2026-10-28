import java.sql.*;

public class CheckSchema {
    static String url  = System.getenv().getOrDefault("CHECK_URL",
            "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres");
    static String user = System.getenv("CHECK_USER");
    static String pass = System.getenv("CHECK_PASS");

    public static void main(String[] args) throws Exception {
        try (Connection c = DriverManager.getConnection(url, user, pass)) {
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
        }
    }
}
