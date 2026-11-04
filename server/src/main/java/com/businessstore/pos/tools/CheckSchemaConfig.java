package com.businessstore.pos.tools;

import java.net.URI;
import java.util.Map;

/**
 * Configuration validation for CheckSchema, separated from the database code so
 * it can be unit-tested without opening a connection.
 *
 * Policy (no defaults, nothing implicit):
 *   CHECK_URL              required; must be jdbc:postgresql://host[:port]/database
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
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw new Rejected("malformed-url", "REFUSED: CHECK_URL must name a host and a database.");
        }
        String hostPort = rest.substring(0, slash).split("\\?", 2)[0];
        if (hostPort.isEmpty() || !hostPort.contains(".")) {
            throw new Rejected("malformed-url", "REFUSED: CHECK_URL must name a fully qualified host.");
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