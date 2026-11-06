import java.sql.*;
import java.util.UUID;

/**
 * Verifies migration 0003 on a live database: the hardened is_admin() and its
 * EXECUTE grants, the view grants, and the dashboard arithmetic (checked with
 * fixture rows inside a transaction that is always rolled back, so the database
 * is left untouched).
 *
 * Env: CHECK_URL / CHECK_USER / CHECK_PASS.
 */
public class Verify0003 {

    static int failures = 0;

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
            c.setAutoCommit(false);

            System.out.println("== is_admin() definition ==");
            q(c, "select pg_get_functiondef('public.is_admin()'::regprocedure) as def");

            System.out.println("== is_admin() ACL (expect only postgres + authenticated + service_role) ==");
            q(c, "select proacl from pg_proc where oid = 'public.is_admin()'::regprocedure");

            System.out.println("== is_admin() EXECUTE privileges ==");
            for (String role : new String[]{"anon", "authenticated", "service_role"}) {
                boolean can = scalarBool(c,
                    "select has_function_privilege('" + role + "', 'public.is_admin()', 'EXECUTE')");
                System.out.println("  " + role + " can execute: " + can);
                expect(role.equals("anon") ? !can : can, role + " execute privilege");
            }

            System.out.println("== view SELECT privileges ==");
            for (String view : new String[]{"dashboard_sales_daily", "low_stock_overview"}) {
                boolean auth = scalarBool(c,
                    "select has_table_privilege('authenticated', 'public." + view + "', 'SELECT')");
                boolean anon = scalarBool(c,
                    "select has_table_privilege('anon', 'public." + view + "', 'SELECT')");
                System.out.println("  " + view + ": authenticated=" + auth + " anon=" + anon);
                expect(auth, view + " readable by authenticated");
                expect(!anon, view + " not readable by anon");
            }

            System.out.println("== view security_invoker ==");
            q(c, "select c.relname, c.reloptions from pg_class c " +
                 "join pg_namespace n on n.oid = c.relnamespace " +
                 "where n.nspname = 'public' and c.relname in " +
                 "('dashboard_sales_daily','low_stock_overview') order by 1");

            System.out.println("== dashboard arithmetic (fixture rows, rolled back) ==");
            arithmetic(c);

            c.rollback();
            System.out.println(failures == 0
                ? "\nALL CHECKS PASSED (transaction rolled back, no rows written)"
                : "\n" + failures + " CHECK(S) FAILED (transaction rolled back)");
            if (failures != 0) System.exit(1);
        }
    }

    /** One sale with two lines + one line-less sale + one refund, same day. */
    static void arithmetic(Connection c) throws Exception {
        String cashier = UUID.randomUUID().toString();
        String milo = UUID.randomUUID().toString();
        String water = UUID.randomUUID().toString();
        String saleA = UUID.randomUUID().toString();
        String saleB = UUID.randomUUID().toString();
        String refund = UUID.randomUUID().toString();
        String tag = "verify0003-" + UUID.randomUUID();
        Timestamp at = Timestamp.valueOf("2026-10-26 12:00:00");

        exec(c, "insert into users (user_id, device_id, full_name, role, mfa_enabled, failed_attempts," +
                " is_active, created_at, updated_at) values (?, null, 'Verify 0003', 'cashier', false, 0," +
                " true, ?, ?)", cashier, at, at);
        exec(c, "insert into products (product_id, device_id, sku, name, unit_price, cost_price, tax_rate_bp," +
                " low_stock_threshold, is_active, created_at, updated_at) values (?, ?, ?, 'Milo', 4500, 3000," +
                " 0, 0, true, ?, ?)", milo, tag, tag + "-MILO", at, at);
        exec(c, "insert into products (product_id, device_id, sku, name, unit_price, cost_price, tax_rate_bp," +
                " low_stock_threshold, is_active, created_at, updated_at) values (?, ?, ?, 'Water', 150, 100," +
                " 0, 0, true, ?, ?)", water, tag, tag + "-WATER", at, at);

        insertSale(c, saleA, cashier, tag, "0001", 8950, 200, 1100, 10050, "sale", null, at);
        exec(c, "insert into sale_items (item_id, sale_id, product_id, sku, name, quantity, unit_price," +
                " discount, tax_rate_bp, tax, line_total, price_source) values (?, ?, ?, 'S', 'Milo', 2," +
                " 4500, 200, 0, 1100, 9900, 'catalog')", UUID.randomUUID().toString(), saleA, milo);
        exec(c, "insert into sale_items (item_id, sale_id, product_id, sku, name, quantity, unit_price," +
                " discount, tax_rate_bp, tax, line_total, price_source) values (?, ?, ?, 'S', 'Water', 1," +
                " 150, 0, 0, 0, 150, 'catalog')", UUID.randomUUID().toString(), saleA, water);

        insertSale(c, saleB, cashier, tag, "0002", 500, 0, 0, 500, "sale", null, at);
        insertSale(c, refund, cashier, tag, "0003", 9900, 0, 0, 9900, "refund", saleA, at);

        try (PreparedStatement ps = c.prepareStatement(
                "select sales_count, revenue, discounts, tax, refunds_total, gross_profit" +
                " from dashboard_sales_daily where day = date '2026-10-26'");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                fail("no dashboard row for 2026-10-26");
                return;
            }
            long count = rs.getLong("sales_count");
            long revenue = rs.getLong("revenue");
            long discounts = rs.getLong("discounts");
            long tax = rs.getLong("tax");
            long refunds = rs.getLong("refunds_total");
            long profit = rs.getLong("gross_profit");
            System.out.println("  got: sales_count=" + count + " revenue=" + revenue +
                " discounts=" + discounts + " tax=" + tax + " refunds=" + refunds +
                " gross_profit=" + profit);
            expect(count == 2, "sales_count = 2 (not one row per line)");
            expect(revenue == 10550, "revenue = 10550 pesewas");
            expect(discounts == 200, "discounts = 200");
            expect(tax == 1100, "tax = 1100");
            expect(refunds == 9900, "refunds_total = 9900");
            expect(profit == 2850, "gross_profit = 2850");
        }
    }

    static void insertSale(Connection c, String id, String cashier, String tag, String receipt,
                           long subtotal, long discount, long tax, long total, String kind,
                           String original, Timestamp at) throws Exception {
        exec(c, "insert into sales (sale_id, device_id, receipt_number, cashier_id, subtotal," +
                " discount_total, tax_total, total, kind, original_sale_id, status, sync_state," +
                " created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'completed', 'synced', ?)",
                id, tag, tag + "-" + receipt, cashier, subtotal, discount, tax, total, kind, original, at);
    }

    static void exec(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
        }
    }

    static boolean scalarBool(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    static void expect(boolean ok, String what) {
        System.out.println("  " + (ok ? "OK   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    static void fail(String what) {
        System.out.println("  FAIL " + what);
        failures++;
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
