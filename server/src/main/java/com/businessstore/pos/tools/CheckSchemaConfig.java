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
 *                          A non-loopback host must set sslmode=verify-full: this
 *                          tool sends CHECK_PASS, and weaker modes would either
 *                          omit TLS or skip verifying the server. Loopback means
 *                          the exact hostname 'localhost' or a strict IPv4 literal
 *                          in 127.0.0.0/8 -- never a host that merely begins
 *                          with "127.", which is a remote DNS name.
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

    /** The sslmode values pgJDBC understands; anything else is a typo, not a setting. */
    private static final java.util.Set<String> KNOWN_SSL_MODES = java.util.Set.of(
        "disable", "allow", "prefer", "require", "verify-ca", "verify-full");

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
        java.util.List<String> sslModes = new java.util.ArrayList<>();
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
                // sslmode is the one parameter allowed through, so it is the one whose
                // value must be judged. It is collected here and validated below, once
                // the host is known.
                String[] pair = parameter.split("=", 2);
                sslModes.add(pair.length == 2 ? pair[1].trim().toLowerCase() : "");
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
        String host = hostPort.split(":", 2)[0].toLowerCase();
        // A fully qualified name is required so a bare label cannot be completed by
        // the local resolver. 'localhost' is the one exception: it is the
        // conventional local name, and it only reaches a weaker TLS mode through
        // the loopback check below, which matches it exactly.
        if (!hostPort.contains(".") && !host.equals("localhost")) {
            throw new Rejected("malformed-url", "REFUSED: CHECK_URL must name a fully qualified host.");
        }
        int colon = hostPort.indexOf(':');
        if (colon >= 0) {
            int port = Integer.parseInt(hostPort.substring(colon + 1));
            if (port < 1 || port > 65535) {
                throw new Rejected("malformed-url", "REFUSED: CHECK_URL names a port outside 1-65535.");
            }
        }
        // A duplicate would make the effective mode depend on which occurrence a
        // parser read, so the TLS rule below could be satisfied by the first one
        // while pgJDBC applied the last.
        if (sslModes.size() > 1) {
            throw new Rejected("duplicate-sslmode",
                "REFUSED: CHECK_URL sets sslmode more than once; the effective value must be " +
                    "unambiguous.");
        }
        String sslMode = sslModes.isEmpty() ? null : sslModes.get(0);
        if (sslMode != null && !KNOWN_SSL_MODES.contains(sslmodeCanonical(sslMode))) {
            throw new Rejected("malformed-url", "REFUSED: CHECK_URL sets an unrecognised sslmode value.");
        }
        boolean loopback = isLoopback(host);
        // This tool authenticates with CHECK_USER / CHECK_PASS. Over a network those
        // credentials need both encryption and a verified server: sslmode=disable
        // sends them in clear, and sslmode=require (or prefer/allow) encrypts without
        // checking the certificate, so an on-path attacker can collect them or
        // impersonate the database. Only a loopback target, where there is no network
        // to intercept, may use a weaker mode.
        if (!loopback && !"verify-full".equals(sslMode)) {
            throw new Rejected("tls-required",
                "REFUSED: a non-loopback CHECK_URL must set sslmode=verify-full. Weaker modes " +
                    "either send the credentials without TLS or encrypt without verifying the " +
                    "server; they are accepted only for a loopback target.");
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

    /**
     * Whether an already-validated host names the local machine.
     *
     * Exactly two shapes qualify: the hostname 'localhost' (compared after
     * lowercasing, never by prefix) and a strict dotted-quad IPv4 literal in
     * 127.0.0.0/8 -- four decimal octets, each 0-255, with no leading zero. A
     * textual prefix is deliberately not used: "127.db.attacker.example" passes the
     * hostname grammar and can resolve anywhere, so treating it as loopback would
     * let a remote target keep sslmode=disable and expose CHECK_PASS. No DNS lookup
     * happens here; resolution cannot make a remote name local, and a name that
     * resolves to loopback today may not tomorrow.
     */
    private static boolean isLoopback(String host) {
        if (host.equals("localhost")) return true;
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) return false;
        int first = -1;
        for (int i = 0; i < octets.length; i++) {
            String octet = octets[i];
            // "" is a shortened form such as "127.1"; more than three digits cannot
            // be in 0-255; a leading zero ('01', '000') is a non-canonical spelling
            // whose meaning differs between parsers.
            if (octet.isEmpty() || octet.length() > 3) return false;
            if (octet.length() > 1 && octet.charAt(0) == '0') return false;
            for (int c = 0; c < octet.length(); c++) {
                if (octet.charAt(c) < '0' || octet.charAt(c) > '9') return false;
            }
            int value = Integer.parseInt(octet);
            if (value > 255) return false;
            if (i == 0) first = value;
        }
        return first == 127;
    }

    /** pgJDBC treats sslmode values case-insensitively and tolerates whitespace. */
    private static String sslmodeCanonical(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static String blankToNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }
}