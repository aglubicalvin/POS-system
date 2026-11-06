import java.sql.*;

public class Verify0001 {
    public static void main(String[] args) throws Exception {
        // No default target: a hard-coded Supabase pooler URL here meant a typo in
        // the environment silently pointed a verification run at a shared project.
        String url = System.getenv("CHECK_URL");
        if (url == null || url.isBlank()) {
            System.err.println("REFUSED: CHECK_URL is not set. This tool has no default target.");
            System.exit(2);
            return;
        }
        try (Connection c = DriverManager.getConnection(url,
                System.getenv("CHECK_USER"), System.getenv("CHECK_PASS"))) {

            System.out.println("== profiles table exists ==");
            q(c, "select count(*) from information_schema.tables " +
                 "where table_schema='public' and table_name='profiles'");

            System.out.println("== function is_admin ==");
            q(c, "select routine_name from information_schema.routines " +
                 "where routine_schema='public' and routine_name='is_admin'");

            System.out.println("== policies (expect 9: 2 on profiles + 7 canonical) ==");
            q(c, "select tablename, policyname, cmd from pg_policies " +
                 "where schemaname='public' order by tablename, policyname");

            System.out.println("== views (expect dashboard_sales_daily + low_stock_overview) ==");
            q(c, "select viewname from pg_views " +
                 "where schemaname='public' order by 1");

            System.out.println("== is_admin executable by authenticated? ==");
            q(c, "select has_function_privilege('authenticated', " +
                 "'public.is_admin()', 'EXECUTE') as authenticated_can_execute");

            System.out.println("== migration bookkeeping row ==");
            q(c, "select version, name, executed_at from supabase_migrations.schema_migrations " +
                 "order by version");

            System.out.println("== select from views as smoke test (0 rows is fine) ==");
            try (Statement st1 = c.createStatement();
                 ResultSet r1 = st1.executeQuery("select count(*) from public.dashboard_sales_daily")) {
                r1.next();
                System.out.println("dashboard_sales_daily rows=" + r1.getInt(1));
            }
            try (Statement st2 = c.createStatement();
                 ResultSet r2 = st2.executeQuery("select count(*) from public.low_stock_overview")) {
                r2.next();
                System.out.println("low_stock_overview rows=" + r2.getInt(1));
            }
        }
    }

    static void q(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int meta = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder("  ");
                for (int i = 1; i <= meta; i++) sb.append(rs.getString(i)).append(" | ");
                sb.setLength(sb.length() - 3);
                System.out.println(sb);
            }
        }
    }
}
