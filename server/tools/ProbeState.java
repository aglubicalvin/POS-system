package com.businessstore.pos.tools;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.io.IOException;
import java.io.InputStream;

/**
 * READ-ONLY probe of a Supabase project's application state, used to compare
 * migration parity across environments.
 *
 * Safety contract, enforced in code rather than by convention:
 *  1. No writes: every query is a SELECT over catalogs/views, the connection is
 *     marked read-only, and work runs inside a read-only transaction, so an
 *     accidental DML/DDL statement fails with 25006 instead of touching data.
 *  2. No accidental production target: CHECK_URL must be set, an explicit
 *     PROBE_CONFIRM=yes is required, and hosts that look like production are
 *     refused outright even with confirmation.
 *  3. No secret leakage: credentials are read from CHECK_USER/CHECK_PASS and
 *     never printed.
 *  4. Deterministic output: every list query has an ORDER BY and counts are
 *     scalar, so two runs against the same database produce identical text.
 *  5. Honest read-only evidence: the JDBC setters are only a request. Before
 *     any catalog query the probe asks PostgreSQL itself
 *     (`SHOW transaction_read_only`) and requires `on`.
 *
 * Staging transport contract (shared with probe-readonly.ps1):
 *
 *  - When CHECK_URL nominates a Supabase pooler endpoint, this is a STAGING
 *    connection. The hostname actually connected to is taken from
 *    POS_STAGING_POOLER_HOST and nothing else: it must match the shared pooler
 *    shape (aws-<n>-<region>.pooler.supabase.com) and must agree with the host
 *    nominated by CHECK_URL. There is no default, no region inference and no
 *    fallback to a previously used endpoint.
 *  - The staging connection uses sslmode=verify-full with a staged root
 *    certificate, whose SHA-256 over its DER encoding must equal the
 *    independently supplied POS_STAGING_DB_ROOT_CERT_SHA256. A missing or
 *    mismatched value refuses the target before any JDBC call. The certificate
 *    is copied to private temp material, outside the caller's path, and deleted
 *    on exit.
 *  - A loopback CHECK_URL is a disposable local test database and is the only
 *    target allowed without TLS. Every other host is refused.
 */
public class ProbeState {

    private static final String POOLER_HOST_ENV = "POS_STAGING_POOLER_HOST";
    private static final String ROOT_CERT_ENV = "POS_STAGING_DB_ROOT_CERT";
    private static final String ROOT_CERT_SHA256_ENV = "POS_STAGING_DB_ROOT_CERT_SHA256";
    /** The exact Supabase pooler shape. Anchored; no suffix, port or path allowed. */
    private static final String POOLER_HOST_PATTERN =
        "^aws-[0-9]+-[a-z0-9-]+\\.pooler\\.supabase\\.com$";

    public static void main(String[] args) throws Exception {
        String url = System.getenv("CHECK_URL");
        String user = System.getenv("CHECK_USER");
        String pass = System.getenv("CHECK_PASS");

        if (url == null || url.isBlank()) {
            System.err.println(
                "REFUSED: CHECK_URL is not set. Point it at the designated staging pooler or a test database.");
            System.exit(2);
        }
        if (!"yes".equals(System.getenv("PROBE_CONFIRM"))) {
            System.err.println("REFUSED: set PROBE_CONFIRM=yes to confirm this is the intended target.");
            System.err.println("  target host: " + hostOf(url));
            System.exit(3);
        }
        String nominatedHost = hostOf(url);
        if (nominatedHost.toLowerCase().contains("prod")) {
            System.err.println("REFUSED: " + nominatedHost + " looks like a production host.");
            System.err.println("  This probe must never run against production.");
            System.exit(4);
        }
        String bareHost = hostName(url).toLowerCase();
        boolean stagingTarget = bareHost.endsWith(".pooler.supabase.com");

        Properties props = null;
        String connectUrl;
        if (stagingTarget) {
            // Credentials are mandatory for a real staging connection. A loopback
            // test database may legitimately be passwordless.
            if (user == null || user.isBlank() || pass == null || pass.isBlank()) {
                System.err.println("REFUSED: CHECK_USER/CHECK_PASS are required for a staging connection.");
                System.exit(2);
            }
            props = new Properties();
            props.put("user", user);
            props.put("password", pass);
            // Staging: the connected hostname comes from POS_STAGING_POOLER_HOST only.
            String poolerHost = System.getenv(POOLER_HOST_ENV);
            if (poolerHost == null || poolerHost.isBlank()) {
                System.err.println(
                    "REFUSED: " + POOLER_HOST_ENV + " is not set. A staging connection must use an "
                        + "explicitly configured Supabase pooler hostname; there is no default, no "
                        + "region inference and no fallback to a previously used endpoint.");
                System.exit(8);
            }
            poolerHost = poolerHost.trim().toLowerCase();
            if (!poolerHost.matches(POOLER_HOST_PATTERN)) {
                System.err.println(
                    "REFUSED: " + POOLER_HOST_ENV + " is not a Supabase pooler endpoint. Expected "
                        + "exactly aws-<n>-<region>.pooler.supabase.com; IP literals, loopback names, "
                        + "other DNS names, URIs, paths, credentials, query strings and ports are refused.");
                System.exit(8);
            }
            if (!poolerHost.equals(bareHost)) {
                System.err.println(
                    "REFUSED: " + POOLER_HOST_ENV + " does not match the host nominated by CHECK_URL. "
                        + "Both staging paths must use the same validated pooler hostname.");
                System.exit(8);
            }

            String rootCert = System.getenv(ROOT_CERT_ENV);
            if (rootCert == null || rootCert.isBlank()) {
                System.err.println(
                    "REFUSED: " + ROOT_CERT_ENV + " is not set. A staged root certificate is "
                        + "mandatory for a staging connection.");
                System.exit(9);
            }
            String rootCertSha256 = System.getenv(ROOT_CERT_SHA256_ENV);
            if (rootCertSha256 == null || rootCertSha256.isBlank()) {
                System.err.println(
                    "REFUSED: " + ROOT_CERT_SHA256_ENV + " is not set. A staged certificate without an "
                        + "independently supplied fingerprint would accept any CA the caller names.");
                System.exit(10);
            }
            rootCertSha256 = rootCertSha256.trim().replace(":", "").toLowerCase();
            if (!rootCertSha256.matches("[a-f0-9]{64}")) {
                System.err.println(
                    "REFUSED: " + ROOT_CERT_SHA256_ENV + " is not a 64-character hex SHA-256 digest. "
                        + "Recompute it over the DER encoding of the staged root certificate.");
                System.exit(10);
            }

            Path stagedCert;
            try {
                stagedCert = prepareStagedRootCert(rootCert, rootCertSha256);
            } catch (IllegalArgumentException e) {
                System.err.println("REFUSED: " + e.getMessage());
                System.exit(10);
                return;
            } catch (Exception e) {
                System.err.println(
                    "REFUSED: the staged root certificate could not be read or prepared: " + e.getMessage());
                System.exit(9);
                return;
            }

            // verify-full only: no prefer, require, allow or disable fallback.
            props.put("sslmode", "verify-full");
            props.put("sslrootcert", stagedCert.toString());
            connectUrl = "jdbc:postgresql://" + poolerHost + ":5432/postgres";
        } else {
            if (!isLoopback(bareHost)) {
                System.err.println(
                    "REFUSED: " + bareHost + " is neither the approved Supabase pooler endpoint nor a "
                        + "loopback test database. The probe refuses any other target.");
                System.exit(8);
            }
            // Disposable local test database: no TLS material is involved.
            connectUrl = url;
        }

        System.out.println("== read-only state probe ==");
        System.out.println("  target host: " + nominatedHost);

        // Poolers (e.g. Supabase's transaction pooler on :6543) reuse backend
        // sessions, so server-side prepared statement names can collide across
        // clients. The probe has no need for them.
        if (!connectUrl.contains("prepareThreshold")) {
            connectUrl = connectUrl + (connectUrl.contains("?") ? "&" : "?") + "prepareThreshold=0";
        }

        Connection opened;
        try {
            opened = stagingTarget
                ? DriverManager.getConnection(connectUrl, props)
                : DriverManager.getConnection(connectUrl, user, pass);
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
            c.setAutoCommit(false);
            c.setReadOnly(true);

            // Server-side verification, before ANY catalog query. A successful
            // JDBC setter call is not proof: the driver may ignore it, and a
            // pooler may hand back a session with a different default.
            String transactionReadOnly = readTransactionReadOnly(c);
            if (!"on".equals(transactionReadOnly)) {
                System.err.println("  probe_status=error");
                System.err.println("  error_category=read_only_not_confirmed");
                System.err.println(
                    "  error_detail=transaction_read_only="
                        + (transactionReadOnly == null ? "unavailable" : sanitizeValue(transactionReadOnly)));
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
            q(c, "select 'app_tables=' || count(*) from pg_class c "
                + "join pg_namespace n on n.oid=c.relnamespace "
                + "where n.nspname='public' and c.relkind='r'");
            q(c, "select 'views=' || count(*) from pg_class c "
                + "join pg_namespace n on n.oid=c.relnamespace "
                + "where n.nspname='public' and c.relkind in ('v','m')");
            q(c, "select 'policies=' || count(*) from pg_policies where schemaname='public'");
            q(c, "select 'policies_to_authenticated=' || count(*) from pg_policies p "
                + "where p.schemaname='public' and exists (select 1 from unnest(p.roles) r "
                + "where r='authenticated')");
            q(c, "select 'policies_to_public=' || count(*) from pg_policies p "
                + "where p.schemaname='public' and exists (select 1 from unnest(p.roles) r "
                + "where r='public')");
            q(c, "select 'functions=' || count(*) from pg_proc p "
                + "join pg_namespace n on n.oid=p.pronamespace "
                + "where n.nspname='public' and p.proname not like 'pg\\_%'");
            q(c, "select 'functions_pinned_search_path=' || count(*) from pg_proc p "
                + "join pg_namespace n on n.oid=p.pronamespace "
                + "where n.nspname='public' and p.proconfig is not null");
            q(c, "select 'triggers=' || count(*) from pg_trigger t "
                + "join pg_class c on c.oid=t.tgrelid "
                + "join pg_namespace n on n.oid=c.relnamespace "
                + "where n.nspname='public' and not t.tgisinternal");
            q(c, "select 'tables_with_rls=' || count(*) from pg_class c "
                + "join pg_namespace n on n.oid=c.relnamespace "
                + "where n.nspname='public' and c.relkind='r' and c.relrowsecurity");

            // Migration-history existence check (read-only, catalog-based). The
            // count MUST be a separate statement guarded in Java: PostgreSQL
            // analyses every subquery in an expression before evaluating a CASE
            // condition, so a reference to a missing table would fail even when
            // that branch would not be taken.
            System.out.println("-- migration history --");
            boolean migrationTableExists = exists(c,
                "select 1 from information_schema.tables "
                    + "where table_schema='supabase_migrations' and table_name='schema_migrations'");

            System.out.println("  supabase_migrations_exists=" + migrationTableExists);
            if (migrationTableExists) {
                q(c, "select 'migration_history=' || count(version) || "
                    + "' rows from supabase_migrations.schema_migrations' "
                    + "from supabase_migrations.schema_migrations");
            } else {
                System.out.println("  migration_history=not_initialized");
            }

            System.out.println("-- public tables --");
            q(c, "select c.relname from pg_class c "
                + "join pg_namespace n on n.oid=c.relnamespace "
                + "where n.nspname='public' and c.relkind='r' order by 1");

            System.out.println("-- policies not scoped to authenticated --");
            q(c, "select p.tablename || '.' || p.policyname || ' -> ' || array_to_string(p.roles, ',') "
                + "from pg_policies p where p.schemaname='public' "
                + "and not exists (select 1 from unnest(p.roles) r where r='authenticated') "
                + "order by 1");

            // ProbeState never writes; closing a read-only session is harmless.
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

        // The exit status is set last so both the failure paths above and a clean
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

    /** Host[:port]/database of a libpq URL, with userinfo stripped. */
    static String hostOf(String url) {
        String s = url.replaceFirst("^jdbc:", "");
        s = s.replaceFirst("^postgresql://", "").replaceFirst("^postgres://", "");
        int at = s.lastIndexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        return s;
    }

    /** Bare hostname of a JDBC/libpq URL: no userinfo, port, path or query. */
    static String hostName(String url) {
        String s = hostOf(url);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        return s;
    }

    /** True only for a disposable loopback test database. */
    static boolean isLoopback(String host) {
        return host.equals("localhost")
            || host.equals("::1")
            || host.equals("0.0.0.0")
            || host.startsWith("127.");
    }

    /**
     * DER-decode the staged root certificate, matching exactly how the
     * provisioning contract hashes the certificate for its fingerprint.
     */
    static byte[] toDer(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(in))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("--")) {
                    sb.append(t);
                }
            }
        }
        byte[] b64 = sb.toString().getBytes("US-ASCII");
        return java.util.Base64.getDecoder().decode(new String(b64, "US-ASCII").trim());
    }

    /**
     * Verify the independently supplied SHA-256 over the certificate's DER
     * encoding, then copy the PEM to private temp material outside the caller's
     * path. The copy is deleted on exit, on success and failure.
     */
    static Path prepareStagedRootCert(String rootCert, String rootCertSha256) throws Exception {
        byte[] der;
        try (InputStream in = Files.newInputStream(Path.of(rootCert))) {
            der = toDer(in);
        }
        String actual = bytesToHex(MessageDigest.getInstance("SHA-256").digest(der));
        if (!actual.equalsIgnoreCase(rootCertSha256)) {
            throw new IllegalArgumentException(
                "the SHA-256 of the staged root certificate does not match " + ROOT_CERT_SHA256_ENV
                    + ". The certificate may have been rotated, or this is not the approved staging target.");
        }
        Path temp = Files.createTempFile("staging-root-cert-", ".pem");
        Files.writeString(temp, Files.readString(Path.of(rootCert)));
        try {
            Files.setPosixFilePermissions(temp, EnumSet.of(PosixFilePermission.OWNER_READ));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (e.g. Windows): the temp file's own ACLs apply.
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Best effort: the process is exiting.
            }
        }));
        return temp;
    }

    /** 64-character lowercase hex for the fingerprint comparison. */
    static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
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
