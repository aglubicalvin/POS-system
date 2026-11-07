package com.businessstore.pos.tools;

import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * Staging transport contract (shared with probe-readonly.ps1 and
 * src/config/staging-target.ts):
 *
 *  - When CHECK_URL nominates a Supabase pooler endpoint, this is a STAGING
 *    connection. The hostname actually connected to is taken from
 *    POS_STAGING_POOLER_HOST and nothing else: it must match the shared pooler
 *    shape (aws-<n>-<region>.pooler.supabase.com) and must agree with the host
 *    nominated by CHECK_URL. There is no default, no region inference and no
 *    fallback to a previously used endpoint.
 *  - Project identity is established separately: POS_STAGING_PROJECT_REF must
 *    be set, must not be a known non-staging project, and CHECK_USER must be
 *    exactly postgres.<that reference>. A valid pooler hostname never stands in
 *    for project identity, because the pooler is shared by every project in a
 *    region.
 *  - The staging connection uses sslmode=verify-full with a staged root
 *    certificate, whose SHA-256 over its DER encoding must equal the
 *    independently supplied POS_STAGING_DB_ROOT_CERT_SHA256. A missing or
 *    mismatched value refuses the target before any JDBC call. The certificate
 *    is copied to private temp material, outside the caller's path, and deleted
 *    on exit.
 *  - A loopback CHECK_URL is a disposable local test database and is the only
 *    target allowed without TLS. Loopback is decided by exact name or by strict
 *    literal parsing, never by DNS resolution and never by a string prefix, so
 *    a name such as 127.db.attacker.example is a remote host and is refused.
 *    The URL authority is validated as a whole, not mined for a plausible host:
 *    a bracketed IPv6 literal is accepted only when nothing but an optional
 *    valid port follows the closing bracket, so [::1]attacker.example:5432 is
 *    unparseable and is refused instead of being read as ::1. Every other host
 *    is refused.
 *
 * Exit codes:
 *   2  CHECK_URL missing, or staging credentials missing
 *   3  PROBE_CONFIRM is not yes
 *   4  the nominated host looks like production
 *   5  the connection failed
 *   6  the server did not confirm a read-only transaction
 *   7  the probe itself failed
 *   8  the target is not a recognised pooler or loopback host, or the pooler
 *      host and CHECK_URL disagree
 *   9  the staged root certificate is missing or unreadable
 *   10 the fingerprint is missing, malformed or does not match the certificate
 *   11 project identity is missing, malformed, non-staging, or disagrees with
 *      CHECK_USER
 */
public class ProbeState {

    private static final String POOLER_HOST_ENV = "POS_STAGING_POOLER_HOST";
    private static final String ROOT_CERT_ENV = "POS_STAGING_DB_ROOT_CERT";
    private static final String ROOT_CERT_SHA256_ENV = "POS_STAGING_DB_ROOT_CERT_SHA256";
    private static final String PROJECT_REF_ENV = "POS_STAGING_PROJECT_REF";
    private static final String NON_STAGING_REFS_ENV = "POS_NON_STAGING_REFS";

    /**
     * The exact Supabase pooler shape, identical to SUPABASE_POOLER_HOST_PATTERN
     * in src/config/staging-target.ts, to StagingPoolerHost.PATTERN and to the
     * pattern in probe-readonly.ps1. Anchored; the value is trimmed and
     * lower-cased before matching, so no suffix, port, path or casing variant
     * can pass.
     */
    private static final String POOLER_HOST_PATTERN =
        "^aws-[0-9]{1,3}-[a-z]{2,}(?:-[a-z0-9]+)*\\.pooler\\.supabase\\.com$";

    /** The pooler administrator login: postgres.<20 lowercase alphanumerics>. */
    private static final Pattern ADMIN_USER_PATTERN =
        Pattern.compile("^postgres\\.([a-z0-9]{20})$");
    /** Supabase project references are 20 lowercase alphanumerics. */
    private static final Pattern PROJECT_REF_PATTERN = Pattern.compile("^[a-z0-9]{20}$");

    /**
     * References that are known NOT to be staging: the documented development
     * project and the former verification project. Mirrors
     * KNOWN_NON_STAGING_REFS in src/config/staging-target.ts and the
     * $KnownNonStaging list in probe-readonly.ps1. Overridable with a
     * comma-separated POS_NON_STAGING_REFS, exactly like the TypeScript module.
     */
    private static final String[] KNOWN_NON_STAGING_REFS = {
        "uemdrtrzlvhdtonccczt", // development
        "numwzjogaanfhlrfgrqk", // former verification project
    };

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
        if (nominatedHost.toLowerCase(Locale.ROOT).contains("prod")) {
            System.err.println("REFUSED: " + nominatedHost + " looks like a production host.");
            System.err.println("  This probe must never run against production.");
            System.exit(4);
        }

        String bareHost = hostName(url).toLowerCase(Locale.ROOT);
        boolean stagingTarget = bareHost.endsWith(".pooler.supabase.com");

        // A pooler administrator login names a project. If that project is known
        // not to be staging, refuse before looking at the target: identity is
        // never allowed to ride on a hostname.
        String adminRef = adminUserProjectRef(user);
        if (adminRef != null && nonStagingRefs().contains(adminRef)) {
            System.err.println(
                "REFUSED: CHECK_USER belongs to project " + adminRef + ", which is configured as a "
                    + "known non-staging project. The probe will not read a development or former "
                    + "verification database.");
            System.exit(11);
        }

        Properties props = null;
        String connectUrl;
        if (stagingTarget) {
            // --- 1. the pooler hostname, from one place only -----------------
            String poolerHost = System.getenv(POOLER_HOST_ENV);
            if (poolerHost == null || poolerHost.isBlank()) {
                System.err.println(
                    "REFUSED: " + POOLER_HOST_ENV + " is not set. A staging connection must use an "
                        + "explicitly configured Supabase pooler hostname; there is no default, no "
                        + "region inference and no fallback to a previously used endpoint.");
                System.exit(8);
            }
            poolerHost = poolerHost.trim().toLowerCase(Locale.ROOT);
            if (!poolerHost.matches(POOLER_HOST_PATTERN)) {
                System.err.println(
                    "REFUSED: " + POOLER_HOST_ENV + " is not a Supabase pooler endpoint. Expected "
                        + "exactly aws-<n>-<region>.pooler.supabase.com, with <n> one to three digits "
                        + "and <region> one or more lowercase letters followed by optional "
                        + "letter/digit/hyphen groups. IP literals, loopback names, other DNS names, "
                        + "URIs, paths, credentials, query strings and ports are refused.");
                System.exit(8);
            }
            if (!poolerHost.equals(bareHost)) {
                System.err.println(
                    "REFUSED: " + POOLER_HOST_ENV + " does not match the host nominated by CHECK_URL. "
                        + "Both staging paths must use the same validated pooler hostname.");
                System.exit(8);
            }

            // --- 2. project identity, independently of the hostname -----------
            String configuredRef = System.getenv(PROJECT_REF_ENV);
            if (configuredRef == null || configuredRef.isBlank()) {
                System.err.println(
                    "REFUSED: " + PROJECT_REF_ENV + " is not set. The pooler hostname identifies the "
                        + "provider, not the project; the staging project must be designated explicitly.");
                System.exit(11);
            }
            configuredRef = configuredRef.trim();
            if (!PROJECT_REF_PATTERN.matcher(configuredRef).matches()) {
                System.err.println(
                    "REFUSED: " + PROJECT_REF_ENV + " is not a valid Supabase project reference "
                        + "(expected 20 lowercase letters or digits).");
                System.exit(11);
            }
            if (nonStagingRefs().contains(configuredRef)) {
                System.err.println(
                    "REFUSED: " + configuredRef + " is configured as a known non-staging project. "
                        + "Set a dedicated staging reference.");
                System.exit(11);
            }
            String expectedUser = "postgres." + configuredRef;
            if (user == null || !user.trim().equals(expectedUser)) {
                System.err.println(
                    "REFUSED: CHECK_USER does not belong to the designated staging project. Expected "
                        + expectedUser + "; a valid pooler hostname is not project identity.");
                System.exit(11);
            }

            // --- 3. credentials ----------------------------------------------
            if (pass == null || pass.isBlank()) {
                System.err.println("REFUSED: CHECK_USER/CHECK_PASS are required for a staging connection.");
                System.exit(2);
            }
            props = new Properties();
            props.put("user", user.trim());
            props.put("password", pass);

            // --- 4. staged TLS material --------------------------------------
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
            rootCertSha256 = rootCertSha256.trim().replace(":", "").toLowerCase(Locale.ROOT);
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
     * Known non-staging project references. Mirrors the TypeScript deny-list:
     * POS_NON_STAGING_REFS replaces the built-in list when it is set.
     */
    static List<String> nonStagingRefs() {
        String raw = System.getenv(NON_STAGING_REFS_ENV);
        List<String> refs = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            refs.addAll(Arrays.asList(KNOWN_NON_STAGING_REFS));
            return refs;
        }
        for (String part : raw.split(",")) {
            String value = part.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) refs.add(value);
        }
        return refs;
    }

    /** The project reference named by a pooler administrator login, or null. */
    static String adminUserProjectRef(String user) {
        if (user == null) return null;
        Matcher m = ADMIN_USER_PATTERN.matcher(user.trim());
        return m.matches() ? m.group(1) : null;
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

    /**
     * Bare hostname of a JDBC/libpq URL: no userinfo, port, path or query.
     *
     * The authority is validated as a WHOLE rather than searched for a plausible
     * host substring. Extracting the text inside the first pair of brackets would
     * accept `[::1]attacker.example:5432` as the loopback host `::1` and hand the
     * raw URL to the driver, which resolves it as the attacker's name; here any
     * character after the closing bracket other than an optional valid port makes
     * the whole authority unparseable.
     *
     * IPv6 literals are accepted only in bracket notation. An unbracketed
     * authority with more than one colon is ambiguous and is reported as
     * unparseable (empty), which every caller refuses rather than guesses.
     */
    static String hostName(String url) {
        String s = hostOf(url);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int query = s.indexOf('?');
        if (query >= 0) s = s.substring(0, query);
        if (s.isEmpty()) return "";

        if (s.charAt(0) == '[') {
            int close = s.indexOf(']');
            if (close < 0) return "";
            String literal = s.substring(1, close);
            if (!isIpv6Literal(literal)) return "";
            String rest = s.substring(close + 1);
            if (rest.isEmpty()) return literal;
            return validPortSuffix(rest) ? literal : "";
        }

        String host;
        String port;
        int first = s.indexOf(':');
        if (first < 0) {
            host = s;
            port = null;
        } else {
            if (s.indexOf(':', first + 1) >= 0) return "";
            host = s.substring(0, first);
            port = s.substring(first + 1);
        }
        // No accepted target contains a bracket in a bare host, and such a host
        // is never passed to the driver on the strength of a guess.
        if (host.isEmpty() || host.indexOf('[') >= 0 || host.indexOf(']') >= 0) return "";
        if (port != null && !isValidPort(port)) return "";
        return host;
    }

    /** ":<port>": the only suffix allowed after a bracketed IPv6 literal. */
    static boolean validPortSuffix(String suffix) {
        return suffix.length() > 1 && suffix.charAt(0) == ':' && isValidPort(suffix.substring(1));
    }

    /**
     * A decimal TCP port in 1-65535. No sign, whitespace, leading zero or
     * non-digit is accepted: two spellings of one endpoint would let a validator
     * and the driver disagree about the destination.
     */
    static boolean isValidPort(String port) {
        if (port == null || port.isEmpty() || port.length() > 5) return false;
        if (port.length() > 1 && port.charAt(0) == '0') return false;
        for (int i = 0; i < port.length(); i++) {
            char ch = port.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        int value = Integer.parseInt(port);
        return value >= 1 && value <= 65535;
    }

    /**
     * True only for a syntactically valid IPv6 address literal (RFC 4291): two
     * to eight 16-bit hexadecimal groups, at most one "::" compression standing
     * for at least one group, and an optional dotted-quad tail in the final two
     * groups. This is a shape check only; accepting an address here does not make
     * it loopback, and only ::1 (expanded or compressed) is treated as such.
     */
    static boolean isIpv6Literal(String text) {
        if (text == null || text.isEmpty() || text.length() > 45) return false;
        if (!text.matches("[0-9A-Fa-f:.]+")) return false;

        int compression = text.indexOf("::");
        // At most one compression marker: "1::2::3" is not an address.
        if (compression >= 0 && text.indexOf("::", compression + 1) >= 0) return false;

        String head = compression >= 0 ? text.substring(0, compression) : text;
        String tail = compression >= 0 ? text.substring(compression + 2) : null;

        int used = 0;
        if (!head.isEmpty()) {
            used = ipv6GroupCount(head, false);
            if (used < 0) return false;
        }
        if (tail == null) return used == 8;
        if (!tail.isEmpty()) {
            int tailGroups = ipv6GroupCount(tail, true);
            if (tailGroups < 0) return false;
            used += tailGroups;
        }
        // The compression must stand for at least one 16-bit group.
        return used <= 7;
    }

    /**
     * Groups in one colon-separated run, counting an embedded dotted quad as two
     * groups, or -1 when the run is malformed. A dotted quad is only valid in the
     * last positions, so it is refused when it appears before a "::" marker.
     */
    private static int ipv6GroupCount(String run, boolean allowDottedQuad) {
        String[] parts = run.split(":", -1);
        int groups = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.indexOf('.') >= 0) {
                if (!allowDottedQuad || i != parts.length - 1) return -1;
                if (!isDottedQuad(part)) return -1;
                groups += 2;
                continue;
            }
            if (part.isEmpty() || part.length() > 4) return -1;
            for (int j = 0; j < part.length(); j++) {
                if (Character.digit(part.charAt(j), 16) < 0) return -1;
            }
            groups++;
        }
        return groups;
    }

    /** Syntactic dotted quad, independent of 127/8. */
    private static boolean isDottedQuad(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            if (part.length() > 1 && part.charAt(0) == '0') return false;
            for (int i = 0; i < part.length(); i++) {
                char ch = part.charAt(i);
                if (ch < '0' || ch > '9') return false;
            }
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    /**
     * True only for a disposable loopback test database.
     *
     * Loopback is decided by exact name or by strict literal parsing. No DNS
     * lookup is performed, and no string prefix is treated as an address, so
     * `127.db.attacker.example` and `127.attacker.example` are remote hosts.
     * `0.0.0.0` is not a loopback destination and is refused.
     */
    static boolean isLoopback(String host) {
        if (host == null) return false;
        String h = host.trim();
        if (h.isEmpty()) return false;
        if (h.equalsIgnoreCase("localhost")) return true;
        if (h.equals("::1") || h.equalsIgnoreCase("0:0:0:0:0:0:0:1")) return true;
        return isIpv4LoopbackLiteral(h);
    }

    /** Strict dotted-quad in 127.0.0.0/8. Rejects leading zeros and out-of-range octets. */
    static boolean isIpv4LoopbackLiteral(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) return false;
            if (part.length() > 1 && part.charAt(0) == '0') return false;
            int value = 0;
            for (int j = 0; j < part.length(); j++) {
                char ch = part.charAt(j);
                if (ch < '0' || ch > '9') return false;
                value = value * 10 + (ch - '0');
            }
            if (value > 255) return false;
            octets[i] = value;
        }
        // 127.0.0.0/8 only. 0.0.0.0 is deliberately excluded.
        return octets[0] == 127;
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
