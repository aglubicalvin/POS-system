package com.businessstore.pos.tools;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/**
 * Configuration validation for ApplyMigration, separated from the database code so
 * it can be unit-tested without opening a connection.
 *
 * ApplyMigration writes schema and migration history, so its target is validated
 * before a connection exists and the validation is fail-closed: nothing has a
 * default. The previous implementation fell back to a hard-coded Supabase pooler
 * URL when CHECK_URL was unset, which meant a typo in the environment could apply
 * migrations to a shared project.
 *
 * Env contract (nothing else is read):
 *   CHECK_URL              jdbc:postgresql://host[:port]/database   (required)
 *   CHECK_USER             role name                                (required)
 *   CHECK_PASS             role password; may be empty only with
 *                          CHECK_PASS_ALLOW_EMPTY=yes               (required)
 *   MIGRATION_CONFIRM      must be exactly "yes"                    (required)
 *   MIGRATION_EXPECTED_TARGET  the project reference the URL must resolve to
 *                          (required; 20 lowercase alphanumerics)
 *   MIGRATION_FILE         path to the .sql file to apply           (required)
 *
 * The expected target is compared against the pooler username suffix
 * (postgres.<project-ref>), the only per-project signal a JDBC URL carries. A
 * non-pooler username cannot prove a project and is refused.
 *
 * No value is ever echoed: a JDBC URL can carry a password in its userinfo.
 */
public final class MigrationConfig {

    public static final String URL_ENV = "CHECK_URL";
    public static final String USER_ENV = "CHECK_USER";
    public static final String PASS_ENV = "CHECK_PASS";
    public static final String PASS_ALLOW_EMPTY_ENV = "CHECK_PASS_ALLOW_EMPTY";
    public static final String CONFIRM_ENV = "MIGRATION_CONFIRM";
    public static final String EXPECTED_ENV = "MIGRATION_EXPECTED_TARGET";
    public static final String FILE_ENV = "MIGRATION_FILE";

    /** The only value of MIGRATION_CONFIRM that proceeds. */
    public static final String CONFIRM_VALUE = "yes";

    private static final java.util.regex.Pattern PROJECT_REF =
        java.util.regex.Pattern.compile("^[a-z0-9]{20}$");
    private static final java.util.regex.Pattern POOLER_USER =
        java.util.regex.Pattern.compile("^postgres\\.([a-z0-9]{20})$");
    /**
     * Migration file names: {@code <digits>_<name>.sql}. Used by
     * parseMigrationFile, which is the single source of the version and name.
     */
    private static final java.util.regex.Pattern MIGRATION_FILENAME =
        java.util.regex.Pattern.compile("^([0-9]+)_([^\\s./\\\\]+)\\.sql$");

    /**
     * The only hostname shape a migration may target.
     *
     * Mirrors ProvisionTarget.SUPABASE_POOLER_HOST. A pooler host is shared by
     * every project in a region, so it identifies the provider but not the
     * project -- necessary, not sufficient.
     */
    private static final java.util.regex.Pattern SUPABASE_POOLER_HOST =
        java.util.regex.Pattern.compile("^aws-[0-9]+-[a-z0-9-]+\\.pooler\\.supabase\\.com$");

    /** Ports the pooler serves: session (5432) and transaction (6543). */
    private static final Set<Integer> SUPABASE_POOLER_PORTS = Set.of(5432, 6543);

    /**
     * Query parameters that can retarget the connection or replace its TLS
     * configuration. The URL reaches pgJDBC unchanged, so these are refused rather
     * than forwarded.
     */
    private static final Set<String> FORBIDDEN_QUERY_PARAMS = Set.of(
        "host", "port", "database", "servers", "user", "password",
        "sslfactory", "sslfactoryarg", "sslhostnameverifier", "sslrootcert",
        "sslcert", "sslkey", "socketfactory", "socketfactoryarg", "options");

    private static final Set<String> ALLOWED_QUERY_PARAMS = Set.of("sslmode");
    /**
     * The only sslmode a migration run may use.
     *
     * A migration sends an administrator credential and writes schema, so the
     * connection must verify BOTH the certificate chain and the hostname:
     *   disable/allow/prefer  no encryption at all
     *   require               encrypted, but accepts ANY certificate, so a
     *                         man-in-the-middle can present its own
     *   verify-ca             chain checked, hostname NOT checked
     *   verify-full           chain and hostname checked  <- the only accepted value
     */
    public static final String REQUIRED_SSL_MODE = "verify-full";

    private MigrationConfig() {}

    public static final class Config {
        public final String url;
        public final String user;
        public final String password;
        public final String expectedTarget;
        public final String migrationFile;
        /** File name only, e.g. "0005_customers_payroll_hirepurchase.sql". */
        public final String migrationFileName;
        /** Leading digits of the file name, e.g. "0005". */
        public final String migrationVersion;
        /** File name without the version prefix and the .sql suffix, e.g. "customers_payroll_hirepurchase". */
        public final String migrationName;

        Config(String url, String user, String password, String expectedTarget,
               String migrationFile, MigrationFile parsed) {
            this.url = url;
            this.user = user;
            this.password = password;
            this.expectedTarget = expectedTarget;
            this.migrationFile = migrationFile;
            this.migrationFileName = parsed.fileName;
            this.migrationVersion = parsed.version;
            this.migrationName = parsed.name;
        }

        /** Safe description for logs: no host, no password, no full URL. */
        public String describe() {
            String withoutQuery = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
            String db = withoutQuery.substring(withoutQuery.lastIndexOf('/') + 1);
            return "database=" + db + " role=<redacted>";
        }
    }

    /**
     * A migration file name split into the parts the CLI uses: leading digits as
     * the version, the rest as the name. Produced by ONE parser so validation,
     * version extraction and name extraction cannot disagree.
     */
    public static final class MigrationFile {
        public final String fileName;
        public final String version;
        public final String name;

        MigrationFile(String fileName, String version, String name) {
            this.fileName = fileName;
            this.version = version;
            this.name = name;
        }
    }

    public static final class Rejected extends Exception {
        public final String code;

        Rejected(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    public static Config resolve(Map<String, String> env) throws Rejected {
        // 1. Explicit confirmation first: nothing else matters without it.
        String confirm = env.get(CONFIRM_ENV);
        if (!CONFIRM_VALUE.equals(confirm)) {
            throw new Rejected("missing-confirmation",
                "REFUSED: " + CONFIRM_ENV + " must be exactly '" + CONFIRM_VALUE + "'. " +
                "Applying a migration writes schema and history and is never implicit.");
        }

        // 2. Target URL: required, well formed, no credential or retargeting params.
        String url = env.get(URL_ENV);
        if (url == null || url.trim().isEmpty()) {
            throw new Rejected("missing-url",
                "REFUSED: " + URL_ENV + " is required. This tool has no default target.");
        }
        url = url.trim();
        if (!url.startsWith("jdbc:postgresql://")) {
            throw new Rejected("malformed-url",
                "REFUSED: " + URL_ENV + " must start with jdbc:postgresql:// .");
        }
        String rest = url.substring("jdbc:postgresql://".length());
        if (rest.contains("@")) {
            throw new Rejected("credentials-in-url",
                "REFUSED: " + URL_ENV + " must not embed credentials; use " + USER_ENV + " and " + PASS_ENV + ".");
        }
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw new Rejected("malformed-url",
                "REFUSED: " + URL_ENV + " must name a host and a database.");
        }
        String hostPort = rest.substring(0, slash).split("\\?", 2)[0];
        if (hostPort.isEmpty() || !hostPort.contains(".")) {
            throw new Rejected("malformed-url",
                "REFUSED: " + URL_ENV + " must name a fully qualified host.");
        }
        if (hostPort.contains(",")) {
            throw new Rejected("multiple-hosts",
                "REFUSED: " + URL_ENV + " names more than one host; the driver would fail over.");
        }

        // The host must be a Supabase pooler endpoint, mirroring
        // ProvisionTarget.SUPABASE_POOLER_HOST. sslmode=verify-full only proves
        // the certificate matches whatever hostname is named; it does not prove
        // the host belongs to Supabase. Without this, a pooler-shaped username on
        // an arbitrary dotted host would pass and receive the authentication
        // exchange.
        String host = hostPort.contains(":")
            ? hostPort.substring(0, hostPort.lastIndexOf(':'))
            : hostPort;
        String portText = hostPort.contains(":")
            ? hostPort.substring(hostPort.lastIndexOf(':') + 1)
            : "5432";
        if (!SUPABASE_POOLER_HOST.matcher(host.toLowerCase()).matches()) {
            throw new Rejected("untrusted-endpoint",
                "REFUSED: " + URL_ENV + " does not target a Supabase pooler endpoint " +
                "(expected aws-<n>-<region>.pooler.supabase.com).");
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            throw new Rejected("malformed-url", "REFUSED: " + URL_ENV + " has a non-numeric port.");
        }
        if (!SUPABASE_POOLER_PORTS.contains(port)) {
            throw new Rejected("untrusted-endpoint",
                "REFUSED: " + URL_ENV + " uses port " + port + "; a Supabase pooler serves 5432 or 6543.");
        }
        try {
            URI uri = new URI(url.substring("jdbc:".length()));
            if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("postgresql")) {
                throw new Rejected("malformed-url", "REFUSED: " + URL_ENV + " could not be parsed.");
            }
        } catch (java.net.URISyntaxException e) {
            throw new Rejected("malformed-url", "REFUSED: " + URL_ENV + " could not be parsed.");
        }
        validateQuery(url);

        // 3. Expected target: required, and it must be provable from the username.
        String expected = env.get(EXPECTED_ENV);
        if (expected == null || expected.trim().isEmpty()) {
            throw new Rejected("missing-expected-target",
                "REFUSED: " + EXPECTED_ENV + " is required. A pooler host is shared by every " +
                "project in the region, so the expected project reference must be stated.");
        }
        expected = expected.trim();
        if (!PROJECT_REF.matcher(expected).matches()) {
            throw new Rejected("malformed-expected-target",
                "REFUSED: " + EXPECTED_ENV + " is not a valid project reference " +
                "(expected 20 lowercase letters or digits).");
        }

        // 4. Credentials.
        String user = env.get(USER_ENV);
        if (user == null || user.trim().isEmpty()) {
            throw new Rejected("missing-user", "REFUSED: " + USER_ENV + " is required.");
        }
        user = user.trim();
        java.util.regex.Matcher pooler = POOLER_USER.matcher(user);
        if (!pooler.matches()) {
            throw new Rejected("malformed-user",
                "REFUSED: " + USER_ENV + " must be the pooler administrator form postgres.<project-ref> " +
                "so the target project can be verified from the username.");
        }
        if (!pooler.group(1).equals(expected)) {
            throw new Rejected("target-mismatch",
                "REFUSED: the username names a different project than " + EXPECTED_ENV + ".");
        }

        String pass = env.get(PASS_ENV);
        boolean allowEmpty = "yes".equals(env.get(PASS_ALLOW_EMPTY_ENV));
        if (pass == null) {
            throw new Rejected("missing-password", "REFUSED: " + PASS_ENV + " is not set.");
        }
        if (pass.isEmpty() && !allowEmpty) {
            throw new Rejected("missing-password",
                "REFUSED: " + PASS_ENV + " is empty. Set " + PASS_ALLOW_EMPTY_ENV + "=yes to allow that.");
        }

        // 5. The migration file must exist before we connect.
        String file = env.get(FILE_ENV);
        if (file == null || file.trim().isEmpty()) {
            throw new Rejected("missing-migration-file", "REFUSED: " + FILE_ENV + " is required.");
        }
        file = file.trim();
        // Split and validate the NAME first: a file called migration.sql,
        // 0001.sql or 0001_.sql cannot be recorded as a CLI migration row, and
        // the failure must happen here rather than after a connection exists.
        MigrationFile parsed = parseMigrationFile(file);
        java.io.File f = new java.io.File(file);
        if (!f.isFile()) {
            throw new Rejected("missing-migration-file",
                "REFUSED: " + FILE_ENV + " does not name an existing file.");
        }
        if (!f.canRead()) {
            throw new Rejected("unreadable-migration-file",
                "REFUSED: " + FILE_ENV + " names a file this process cannot read.");
        }

        return new Config(url, user, pass, expected, file, parsed);
    }

    /**
     * The ONLY parser for migration file names, shared by validation, version
     * extraction and name extraction.
     *
     * Accepted: {@code <digits>_<non-empty-name>.sql}, e.g.
     * {@code 0005_customers_payroll_hirepurchase.sql}. The name may not be
     * empty, contain whitespace, a path separator or another dot, so every
     * accepted file maps back to exactly one {@code <version>_<name>.sql} the
     * CLI can write out again. Rejected: {@code migration.sql} (no version),
     * {@code 0001.sql} (no name), {@code _admin.sql} / {@code 0001_.sql} (empty
     * parts), {@code abc_admin.sql} (non-numeric version),
     * {@code 0001_admin.sql.bak} and {@code 0001_admin} (not a .sql file).
     */
    public static MigrationFile parseMigrationFile(String path) throws Rejected {
        String fileName;
        try {
            java.nio.file.Path name = java.nio.file.Path.of(path).getFileName();
            if (name == null) {
                throw new Rejected("malformed-migration-file",
                    "REFUSED: " + FILE_ENV + " does not name a file.");
            }
            fileName = name.toString();
        } catch (java.nio.file.InvalidPathException e) {
            throw new Rejected("malformed-migration-file",
                "REFUSED: " + FILE_ENV + " is not a usable path.");
        }
        java.util.regex.Matcher m = MIGRATION_FILENAME.matcher(fileName);
        if (!m.matches()) {
            throw new Rejected("malformed-migration-file",
                "REFUSED: " + FILE_ENV + " must be named <digits>_<name>.sql, for example " +
                "0005_customers_payroll_hirepurchase.sql. The version and name are recorded " +
                "in supabase_migrations.schema_migrations and must round-trip through the CLI.");
        }
        return new MigrationFile(fileName, m.group(1), m.group(2));
    }

    /** Reject retargeting or TLS-replacing query parameters, and unknown keys. */
    private static void validateQuery(String url) throws Rejected {
        String query = url.contains("?") ? url.substring(url.indexOf('?') + 1) : "";
        // NOTE: no early return for an empty query. A URL with no query string has
        // no sslmode, and the check below must reject it -- returning here would
        // let an unencrypted connection through.
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            String rawKey = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
            rawKey = rawKey.trim();
            // pgJDBC matches query keys CASE-SENSITIVELY: "SSLMODE=verify-full" is
            // ignored by the driver, which then falls back to its default (no
            // certificate or hostname verification). Lowercasing the key here would
            // accept a spelling the driver does not honour, so a case-variant key is
            // rejected outright rather than normalised.
            if (!rawKey.equals(rawKey.toLowerCase())) {
                throw new Rejected("case-variant-parameter",
                    "REFUSED: " + URL_ENV + " uses the parameter '" + rawKey + "' with " +
                    "non-lowercase characters. pgJDBC matches parameter names case-sensitively, " +
                    "so this spelling would be ignored by the driver. Use lowercase 'sslmode'.");
            }
            String key = rawKey;
            if (key.isEmpty()) {
                throw new Rejected("malformed-url", "REFUSED: " + URL_ENV + " has an empty query parameter name.");
            }
            if (FORBIDDEN_QUERY_PARAMS.contains(key)) {
                throw new Rejected("forbidden-query-parameter",
                    "REFUSED: " + URL_ENV + " must not set '" + key + "'; it can retarget the " +
                    "connection or replace its TLS configuration.");
            }
            if (!ALLOWED_QUERY_PARAMS.contains(key)) {
                throw new Rejected("unknown-query-parameter",
                    "REFUSED: " + URL_ENV + " contains the unsupported parameter '" + key + "'.");
            }
            seen.merge(key, 1, Integer::sum);
        }
        if (seen.getOrDefault("sslmode", 0) > 1) {
            throw new Rejected("duplicate-sslmode",
                "REFUSED: " + URL_ENV + " sets sslmode more than once.");
        }
        // Read from the original-case query. Lowercasing first would find
        // "SSLMODE=" and accept a value the driver never sees.
        String mode = query;
        int i = mode.indexOf("sslmode=");
        if (i < 0) {
            throw new Rejected("tls-required",
                "REFUSED: " + URL_ENV + " must set sslmode=" + REQUIRED_SSL_MODE + ". " +
                "A migration sends an administrator credential and writes schema, so the " +
                "connection must verify the server certificate and hostname.");
        }
        String value = mode.substring(i + "sslmode=".length());
        int amp = value.indexOf('&');
        if (amp >= 0) value = value.substring(0, amp);
        value = value.trim();
        if (!REQUIRED_SSL_MODE.equals(value)) {
            throw new Rejected("tls-weak-sslmode",
                "REFUSED: " + URL_ENV + " sets sslmode=" + value + "; only " +
                REQUIRED_SSL_MODE + " verifies both the certificate chain and the hostname.");
        }
    }
}