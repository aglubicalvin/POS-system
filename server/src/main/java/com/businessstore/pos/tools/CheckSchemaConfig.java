package com.businessstore.pos.tools;

import java.net.URI;
import java.util.Map;

/**
 * Configuration validation for CheckSchema, separated from the database code so
 * it can be unit-tested without opening a connection.
 *
 * Policy (no defaults, nothing implicit):
 *   CHECK_URL              required; must be jdbc:postgresql://host[:port]/database.
 *                          Only the 'sslmode' query parameter is accepted: the URL
 *                          reaches pgJDBC unchanged, and a query parameter such as
 *                          ?host= would replace the authority validated here.
 *   CHECK_USER             required; never inferred from the URL
 *   CHECK_PASS             required unless CHECK_PASS_ALLOW_EMPTY=yes is explicitly
 *                          set (local trust/peer auth on a development machine).
 *                          An empty password is a configuration decision, not an
 *                          accident, so it must be stated.
 *   CHECK_EXPECTED_USER    optional; when present the CONNECTED current_user must
 *                          equal it, otherwise the session is rejected.
 *   CHECK_EXPECTED_DATABASE optional; same, for current_database().
 *
 * No value is ever echoed: a JDBC URL can carry a password in its userinfo.
 */
public final class CheckSchemaConfig {

    /**
     * The only query parameter CheckSchema accepts.
     *
     * CheckSchema passes the raw URL to pgJDBC, and the driver lets a query
     * parameter REPLACE the authority it was given: `?host=example.test` would send
     * CHECK_USER and CHECK_PASS to a host this validator never checked. So anything
     * that can retarget the connection (host, port, database, servers) or replace
     * its TLS configuration (sslfactory, sslhostnameverifier, sslrootcert, socket
     * factories) is refused rather than forwarded. This list is short on purpose:
     * widening it needs a documented reason and a test that the driver cannot use
     * the new parameter to escape the validated authority.
     */
    private static final java.util.Set<String> ALLOWED_QUERY_PARAMS = java.util.Set.of("sslmode");

    private CheckSchemaConfig() {}

    public static final class Config {
        public final String url;
        public final String user;
        public final String password;
        public final String expectedUser;      // nullable
        public final String expectedDatabase;  // nullable

        Config(String url, String user, String password, String expectedUser, String expectedDatabase) {
            this.url = url;
            this.user = user;
            this.password = password;
            this.expectedUser = expectedUser;
            this.expectedDatabase = expectedDatabase;
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
        String url = env.get("CHECK_URL");
        if (url == null || url.trim().isEmpty()) {
            throw new Rejected("missing-url", "REFUSED: CHECK_URL is required. This tool has no default target.");
        }
        url = url.trim();
        if (!url.startsWith("jdbc:postgresql://")) {
            throw new Rejected("malformed-url", "REFUSED: CHECK_URL must start with jdbc:postgresql:// .");
        }
        String rest = url.substring("jdbc:postgresql://".length());
        if (rest.contains("@")) {
            throw new Rejected("credentials-in-url",
                "REFUSED: CHECK_URL must not embed credentials; use CHECK_USER and CHECK_PASS.");
        }
        // Userinfo is not the only place a credential can hide: pgJDBC also accepts
        // these as URL query parameters, which would sidestep the CHECK_USER /
        // CHECK_PASS contract entirely and put a secret into a value that can end up
        // in driver diagnostics. Refuse them as a URL parameter, exactly as for
        // userinfo.
        int queryStart = rest.indexOf('?');
        if (queryStart >= 0) {
            String query = rest.substring(queryStart + 1);
            for (String parameter : query.split("&")) {
                String name = parameter.split("=", 2)[0].trim().toLowerCase();
                if (name.equals("password") || name.equals("user") || name.equals("sslpassword")) {
                    throw new Rejected("credentials-in-url",
                        "REFUSED: CHECK_URL must not carry credentials in its query parameters; " +
                            "use CHECK_USER and CHECK_PASS.");
                }
                // The URL is not rebuilt from the validated parts: it is handed to
                // the driver, so a parameter pgJDBC honours would win over the
                // authority validated above. The name is not echoed -- it can carry
                // arbitrary caller text.
                if (!ALLOWED_QUERY_PARAMS.contains(name)) {
                    throw new Rejected("unsupported-query-parameter",
                        "REFUSED: CHECK_URL must not carry connection overrides in its query " +
                            "parameters; only 'sslmode' is accepted, because the host, port and " +
                            "database are validated from the URL authority.");
                }
            }
        }
        // pgJDBC reads a comma as a host-list separator and connects to each
        // endpoint in turn, so "db.example.test:5432,attacker.example.test:5432"
        // fails over to a server this validator never approved -- CHECK_USER and
        // CHECK_PASS would be offered to it before verifyIdentity can run. No
        // supported URL needs a comma, so one is refused before the driver sees the
        // string at all; validating only the first host would leave the rest of the
        // list trusted.
        if (rest.indexOf(',') >= 0) {
            throw new Rejected("multiple-hosts",
                "REFUSED: CHECK_URL must name exactly one host. pgJDBC treats a comma as " +
                    "a failover list, so the driver could otherwise connect to an endpoint " +
                    "that was never validated. Use a single-host URL.");
        }
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw new Rejected("malformed-url", "REFUSED: CHECK_URL must name a host and a database.");
        }
        String hostPort = rest.substring(0, slash).split("\\?", 2)[0];
        // Exactly one host, at most one numeric port. Anything else is refused
        // rather than interpreted: whitespace, a second colon (which would be a
        // port list), IPv6 brackets, or a percent-encoded separator all describe an
        // authority this tool cannot reason about by inspection, and guessing is how
        // a client ends up somewhere it never validated.
        if (!hostPort.matches("[A-Za-z0-9.\\-]+(:[0-9]{1,5})?")) {
            throw new Rejected("malformed-url",
                "REFUSED: CHECK_URL must be jdbc:postgresql://<host>[:port]/<database> with " +
                    "one host, an optional numeric port, and no extra characters in the host.");
        }
        if (!hostPort.contains(".")) {
            throw new Rejected("malformed-url", "REFUSED: CHECK_URL must name a fully qualified host.");
        }
        int colon = hostPort.indexOf(':');
        if (colon >= 0) {
            int port = Integer.parseInt(hostPort.substring(colon + 1));
            if (port < 1 || port > 65535) {
                throw new Rejected("malformed-url", "REFUSED: CHECK_URL names a port outside 1-65535.");
            }
        }
        try {
            URI uri = new URI(url.substring("jdbc:".length()));
            if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("postgresql")) {
                throw new Rejected("malformed-url", "REFUSED: CHECK_URL could not be parsed.");
            }
        } catch (java.net.URISyntaxException e) {
            throw new Rejected("malformed-url", "REFUSED: CHECK_URL could not be parsed.");
        }

        String user = env.get("CHECK_USER");
        if (user == null || user.trim().isEmpty()) {
            throw new Rejected("missing-user", "REFUSED: CHECK_USER is required and is never inferred.");
        }

        String pass = env.get("CHECK_PASS");
        boolean allowEmpty = "yes".equals(env.get("CHECK_PASS_ALLOW_EMPTY"));
        if (pass == null) {
            throw new Rejected("missing-password",
                "REFUSED: CHECK_PASS is not set. For a local trust/peer-auth database set " +
                "CHECK_PASS_ALLOW_EMPTY=yes and CHECK_PASS='' explicitly.");
        }
        if (pass.isEmpty() && !allowEmpty) {
            throw new Rejected("missing-password",
                "REFUSED: CHECK_PASS is empty. Set CHECK_PASS_ALLOW_EMPTY=yes to allow an empty password.");
        }

        String expectedUser = blankToNull(env.get("CHECK_EXPECTED_USER"));
        String expectedDatabase = blankToNull(env.get("CHECK_EXPECTED_DATABASE"));
        return new Config(url, user.trim(), pass, expectedUser, expectedDatabase);
    }

    /**
     * Compare the CONNECTED session against the expected identity. Fails closed
     * when an expectation is configured and the session disagrees.
     */
    public static void verifyIdentity(Config config, String actualUser, String actualDatabase) throws Rejected {
        if (config.expectedUser != null && !config.expectedUser.equals(actualUser)) {
            throw new Rejected("identity-mismatch", "REFUSED: connected role is not CHECK_EXPECTED_USER.");
        }
        if (config.expectedDatabase != null && !config.expectedDatabase.equals(actualDatabase)) {
            throw new Rejected("identity-mismatch", "REFUSED: connected database is not CHECK_EXPECTED_DATABASE.");
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }
}