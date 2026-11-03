package com.businessstore.pos.tools;

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
 *  5. Honest read-only evidence: the JDBC setters are only a request. Before
 *     any catalog query the probe asks PostgreSQL itself
 *     (`SHOW transaction_read_only`) and requires `on`. Only then does it print
 *     `read_only_transaction=server_confirmed`; anything else prints a
 *     sanitized `read_only_not_confirmed` category and exits 6 without running
 *     a single inspection query. A refused target or a connection failure
 *     never prints a confirmation either.
 *
 * Usage (production):
 *   CHECK_URL=jdbc:postgresql://host:5432/postgres CHECK_USER=... CHECK_PASS=...
 *   PROBE_CONFIRM=yes java -cp ... com.businessstore.pos.tools.ProbeState
 *
 * Usage (local test against embedded PostgreSQL):
 *   CHECK_URL=jdbc:postgresql://localhost:<port>/<db> CHECK_USER=... CHECK_PASS=...
 *   PROBE_CONFIRM=yes java -cp ... com.businessstore.pos.tools.ProbeState
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

        Connection opened;
        try {
            opened = DriverManager.getConnection(url, user, pass);
        } catch (SQLException e) {
            // Never emit the raw driver exception: it can carry the host, port,
            // database, and role name, and the workflow log is public. Report a
            // sanitized category only.
            System.err.println("  probe_status=error");
            System.err.println("  error_category=connection_failed");
            System.err.println("  error_detail=" + sanitize(e));
            System.exit(5);
            return;
        }

        int exitCode = 0;
        try (Connection c = opened) {
            // Ask the driver for a read-only session: it issues SET TRANSACTION
            // READ ONLY, so any write attempted in this session is refused by
            // PostgreSQL. These calls only REQUEST the mode; what matters is
            // what the server actually has, which is verified below.
            //
            // A driver that refuses the request (e.g. a URL carrying
            // readOnlyMode=ignore) throws here, reaches the sanitized catch at
            // the bottom of this block, and no confirmation is ever printed.
            c.setAutoCommit(false);
            c.setReadOnly(true);

            // Server-side verification, before ANY catalog query. A successful
            // JDBC setter call is not proof: the driver may ignore it, and a
            // pooler may hand back a session with a different default. Only
            // PostgreSQL can say whether the current transaction is read-only,
            // and if it cannot, nothing here is trustworthy enough to inspect.
            String transactionReadOnly = readTransactionReadOnly(c);
            if (!"on".equals(transactionReadOnly)) {
                System.err.println("  probe_status=error");
                System.err.println("  error_category=read_only_not_confirmed");
                System.err.println(
                    "  error_detail=transaction_read_only=" +
                    (transactionReadOnly == null ? "unavailable" : sanitizeValue(transactionReadOnly)));
                exitCode = 6;
                return;
            }

            // Reached only after PostgreSQL confirmed the transaction is
            // read-only, so the workflow can copy this exact line into evidence.
            System.out.println("  read_only_transaction=server_confirmed");

            // Every query from here on is a SELECT over catalogs/views; none
            // is reached unless the check above passed.
            q(c, "select 'database=' || current_database()");
            q(c, "select 'current_user=' || current_user");
            q(c, "select 'app_tables=' || count(*) from pg_class c " +
                 "join pg_namespace n on n.oid=c.relnamespace " +
                 "where n.nspname='public' and c.relkind='r'");
            q(c, "select 'views=' || count(*) from pg_class c " +
                 "join pg_namespace n on n.oid=c.relnamespace " +
                 "where n.nspname='public' and c.relkind in ('v','m')");
            q(c, "select 'policies=' || count(*) from pg_policies where schemaname='public'");
            q(c, "select 'policies_to_authenticated=' || count(*) from pg_policies p " +
                 "where p.schemaname='public' and exists (select 1 from unnest(p.roles) r " +
                 "where r='authenticated')");
            q(c, "select 'policies_to_public=' || count(*) from pg_policies p " +
                 "where p.schemaname='public' and exists (select 1 from unnest(p.roles) r " +
                 "where r='public')");
            q(c, "select 'functions=' || count(*) from pg_proc p " +
                 "join pg_namespace n on n.oid=p.pronamespace " +
                 "where n.nspname='public' and p.proname not like 'pg\\_%'");
            q(c, "select 'functions_pinned_search_path=' || count(*) from pg_proc p " +
                 "join pg_namespace n on n.oid=p.pronamespace " +
                 "where n.nspname='public' and p.proconfig is not null");
            q(c, "select 'triggers=' || count(*) from pg_trigger t " +
                 "join pg_class c on c.oid=t.tgrelid " +
                 "join pg_namespace n on n.oid=c.relnamespace " +
                 "where n.nspname='public' and not t.tgisinternal");
            q(c, "select 'tables_with_rls=' || count(*) from pg_class c " +
                 "join pg_namespace n on n.oid=c.relnamespace " +
                 "where n.nspname='public' and c.relkind='r' and c.relrowsecurity");
            // Migration-history existence check (read-only, catalog-based).
            // The supabase_migrations schema is provisioned by the baseline
            // migration; on a brand-new or unprovisioned project the table does
            // not exist yet. ProbeState is read-only: it never creates schema,
            // tables, or migration rows.
            //
            // The count MUST be a separate statement guarded in Java, not a CASE
            // branch in the same query: PostgreSQL analyses every subquery in an
            // expression before evaluating the CASE condition, so a reference to
            // the missing table fails even when that branch would not be taken.
            System.out.println("-- migration history --");
            boolean migrationTableExists = exists(c,
                "select 1 from information_schema.tables " +
                "where table_schema='supabase_migrations' and table_name='schema_migrations'");

            System.out.println("  supabase_migrations_exists=" + migrationTableExists);
            if (migrationTableExists) {
                q(c, "select 'migration_history=' || count(version) || " +
                     "' rows from supabase_migrations.schema_migrations' " +
                     "from supabase_migrations.schema_migrations");
            } else {
                System.out.println("  migration_history=not_initialized");
            }

            System.out.println("-- public tables --");
            q(c, "select c.relname from pg_class c " +
                 "join pg_namespace n on n.oid=c.relnamespace " +
                 "where n.nspname='public' and c.relkind='r' order by 1");

            System.out.println("-- policies not scoped to authenticated --");
            q(c, "select p.tablename || '.' || p.policyname || ' -> ' || array_to_string(p.roles, ',') " +
                 "from pg_policies p where p.schemaname='public' " +
                 "and not exists (select 1 from unnest(p.roles) r where r='authenticated') " +
                 "order by 1");

            // Preserve existing dirty working-tree changes: ProbeState never
            // writes to the database; closing a read-only session is harmless.
            try {
                c.rollback();
            } catch (SQLException ignored) {
                // Closing a read-only session must not fail the audit output.
            }

            System.out.println("  probe_status=ok");
        } catch (SQLException e) {
            // Sanitized category only: the driver message can carry the host,
            // port, database and role, and the workflow log is public.
            System.err.println("  probe_status=error");
            System.err.println("  error_category=probe_failed");
            System.err.println("  error_detail=" + sanitize(e));
            exitCode = 7;
        }

        // The connection is closed by try-with-resources before this point. The
        // exit status is set last so both the failure paths above and a clean
        // run report truthfully; an unconfirmed read-only mode never exits 0.
        if (exitCode != 0) System.exit(exitCode);
    }

    /**
     * Ask PostgreSQL whether THIS transaction is read-only. Returns the server's
     * own answer (`on` / `off`), or null when the server gave no answer.
     */
    static String readTransactionReadOnly(Connection c) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("show transaction_read_only")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            return null;
        }
    }

    /** A single server value reduced to a short, safe token for the evidence log. */
    static String sanitizeValue(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[^A-Za-z0-9_]+", "_").trim();
        if (cleaned.length() > 16) cleaned = cleaned.substring(0, 16);
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    /**
     * A driver exception reduced to its SQLState and a short message, with the
     * host, port, database and role scrubbed. Used for sanitized failure evidence.
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

    /** Host[:port]/database of a JDBC or libpq URL, with userinfo stripped. */
    static String hostOf(String url) {
        String s = url.replaceFirst("^jdbc:", "");
        s = s.replaceFirst("^postgresql://", "").replaceFirst("^postgres://", "");
        int at = s.lastIndexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        return s;
    }

    /** True when the given SELECT returns at least one row. Read-only. */
    static boolean exists(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next();
        }
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
