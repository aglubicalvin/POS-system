import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * READ-ONLY probe of a Supabase project's application state, used to compare
 * migration parity across environments (verify project, dev, and — only with
 * explicit authorization — production).
 *
 * Safety contract, enforced in code rather than by convention:
 *  1. No writes: every query is a SELECT over catalogs/views, the connection is
 *     marked read-only, and work runs inside a read-only transaction, so an
 *     accidental DML/DDL statement fails with 25006 instead of touching data.
 *  2. No accidental production target: CHECK_URL must be set, an explicit
 *     PROBE_CONFIRM=yes is required, and hosts that look like production are
 *     refused outright even with confirmation.
 *  3. No secret leakage: credentials are read from CHECK_USER/CHECK_PASS and
 *     never printed; only the host and database name are echoed, with any
 *     userinfo component of the URL stripped.
 *  4. Deterministic output: every list query has an ORDER BY and counts are
 *     scalar, so two runs against the same database produce identical text.
 *
 * Usage:
 *   CHECK_URL=jdbc:postgresql://host:5432/postgres CHECK_USER=... CHECK_PASS=...
 *   PROBE_CONFIRM=yes java -cp .:<postgresql.jar> ProbeState
 */
public class ProbeState {
    public static void main(String[] args) throws Exception {
        String url = System.getenv("CHECK_URL");
        String user = System.getenv("CHECK_USER");
        String pass = System.getenv("CHECK_PASS");

        if (url == null || url.isBlank()) {
            System.err.println("REFUSED: CHECK_URL is not set. Point it at a test database.");
            System.exit(2);
        }
        if (!"yes".equals(System.getenv("PROBE_CONFIRM"))) {
            System.err.println("REFUSED: set PROBE_CONFIRM=yes to confirm this is a test database.");
            System.err.println("  target host: " + hostOf(url));
            System.exit(3);
        }
        if (hostOf(url).toLowerCase().contains("prod")) {
            System.err.println("REFUSED: " + hostOf(url) + " looks like a production host.");
            System.err.println("  This probe must never run against production.");
            System.exit(4);
        }

        System.out.println("== read-only state probe ==");
        System.out.println("  target host: " + hostOf(url));

        // Poolers (e.g. Supabase's transaction pooler on :6543) reuse backend
        // sessions, so server-side prepared statement names can collide across
        // clients. The probe has no need for them.
        if (!url.contains("prepareThreshold")) {
            url = url + (url.contains("?") ? "&" : "?") + "prepareThreshold=0";
        }

        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            // Belt and braces: the driver issues SET TRANSACTION READ ONLY, so
            // any write attempted in this session is refused by PostgreSQL.
            c.setAutoCommit(false);
            c.setReadOnly(true);

            q(c, "select 'database=' || current_database()");
            q(c, "select 'current_user=' || current_user");
            q(c, "select 'app_tables=' || count(*) from information_schema.tables " +
                 "where table_schema='public' and table_type='BASE TABLE'");
            q(c, "select 'views=' || count(*) from information_schema.views where table_schema='public'");
            q(c, "select 'policies=' || count(*) from pg_policies where schemaname='public'");
            q(c, "select 'policies_to_authenticated=' || count(*) from pg_policies p " +
                 "where p.schemaname='public' and exists (select 1 from unnest(p.roles) r " +
                 "where r='authenticated')");
            q(c, "select 'policies_to_public=' || count(*) from pg_policies p " +
                 "where p.schemaname='public' and exists (select 1 from unnest(p.roles) r " +
                 "where r='public')");
            q(c, "select 'functions=' || count(*) from information_schema.routines " +
                 "where routine_schema='public' and routine_name not like 'pg\\_%'");
            q(c, "select 'functions_pinned_search_path=' || count(*) from pg_proc p " +
                 "join pg_namespace n on n.oid=p.pronamespace " +
                 "where n.nspname='public' and p.proconfig is not null");
            q(c, "select 'triggers=' || count(*) from information_schema.triggers " +
                 "where trigger_schema='public'");
            q(c, "select 'tables_with_rls=' || count(*) from pg_class c " +
                 "join pg_namespace n on n.oid=c.relnamespace " +
                 "where n.nspname='public' and c.relkind='r' and c.relrowsecurity");
            q(c, "select 'migration_rows=' || coalesce((select count(*) from " +
                 "supabase_migrations.schema_migrations), 0)");

            System.out.println("-- public tables --");
            q(c, "select table_name from information_schema.tables " +
                 "where table_schema='public' and table_type='BASE TABLE' order by 1");

            System.out.println("-- migration history --");
            q(c, "select version || ' ' || name from " +
                 "supabase_migrations.schema_migrations order by version");

            System.out.println("-- policies not scoped to authenticated --");
            q(c, "select p.tablename || '.' || p.policyname || ' -> ' || array_to_string(p.roles, ',') " +
                 "from pg_policies p where p.schemaname='public' " +
                 "and not exists (select 1 from unnest(p.roles) r where r='authenticated') " +
                 "order by 1");

            try {
                c.rollback();
            } catch (SQLException ignored) {
                // Closing a read-only session must not fail the audit output.
            }
        }
    }

    /** Host[:port]/database of a JDBC or libpq URL, with userinfo stripped. */
    static String hostOf(String url) {
        String s = url.replaceFirst("^jdbc:", "");
        s = s.replaceFirst("^postgresql://", "").replaceFirst("^postgres://", "");
        int at = s.lastIndexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        return s;
    }

    static void q(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            List<String> rows = new ArrayList<>();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder("  ");
                for (int i = 1; i <= n; i++) sb.append(rs.getString(i)).append(" | ");
                sb.setLength(sb.length() - 3);
                rows.add(sb.toString());
            }
            rows.forEach(System.out::println);
        }
    }
}
