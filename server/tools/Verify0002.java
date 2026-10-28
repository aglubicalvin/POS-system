import java.sql.*;

public class Verify0002 {
    public static void main(String[] args) throws Exception {
        String url = System.getenv().getOrDefault("CHECK_URL",
            "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres");
        try (Connection c = DriverManager.getConnection(url,
                System.getenv("CHECK_USER"), System.getenv("CHECK_PASS"))) {

            System.out.println("== new tables (expect product_categories + product_price_audit) ==");
            q(c, "select table_name from information_schema.tables " +
                 "where table_schema='public' and table_name in " +
                 "('product_categories','product_price_audit') order by 1");

            System.out.println("== new routines (expect admin_update_product_price + admin_set_product_category) ==");
            q(c, "select routine_name from information_schema.routines " +
                 "where routine_schema='public' and routine_name like 'admin%' order by 1");

            System.out.println("== policies on the two new tables ==");
            q(c, "select tablename, policyname, cmd from pg_policies " +
                 "where tablename in ('product_categories','product_price_audit') " +
                 "order by tablename, policyname");

            System.out.println("== execute grants on RPCs ==");
            q(c, "select has_function_privilege('authenticated', " +
                 "'public.admin_update_product_price(text,bigint,text)', 'EXECUTE') as price_rpc_ok");
            q(c, "select has_function_privilege('authenticated', " +
                 "'public.admin_set_product_category(text,text)', 'EXECUTE') as category_rpc_ok");

            System.out.println("== anon should NOT have RPC grants (expect f) ==");
            q(c, "select has_function_privilege('anon', " +
                 "'public.admin_update_product_price(text,bigint,text)', 'EXECUTE') as anon_price_rpc");

            System.out.println("== view admin_product_catalog ==");
            q(c, "select viewname from pg_views where schemaname='public' order by 1");
            try (Statement st = c.createStatement();
                 ResultSet r = st.executeQuery("select count(*) from public.admin_product_catalog")) {
                r.next();
                System.out.println("admin_product_catalog rows=" + r.getInt(1));
            }

            System.out.println("== is_admin still intact from 0001 ==");
            q(c, "select has_function_privilege('authenticated', 'public.is_admin()', 'EXECUTE')");

            System.out.println("== migration bookkeeping ==");
            q(c, "select version, name, executed_at from supabase_migrations.schema_migrations order by version");
        }
    }

    static void q(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder("  ");
                for (int i = 1; i <= n; i++) sb.append(rs.getString(i)).append(" | ");
                sb.setLength(sb.length() - 3);
                System.out.println(sb);
            }
        }
    }
}
