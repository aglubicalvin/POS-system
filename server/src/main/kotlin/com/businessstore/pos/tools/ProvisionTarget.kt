package com.businessstore.pos.tools

import java.net.URI

/**
 * The exact database a provisioning run is allowed to touch.
 *
 * Provisioning is the one operation in this server that writes schema, so its
 * target is validated before a connection is opened and the validation is
 * fail-closed: every requirement below must be satisfied explicitly, and nothing
 * has a default. There is deliberately no fallback to DATABASE_URL, to a local
 * database, or to any environment-specific host.
 *
 * Env contract (nothing else is read):
 *   POS_DATABASE_URL      jdbc:postgresql://<host>[:port]/<database>  (required)
 *   POS_DATABASE_USER     role name                                   (required)
 *   POS_DATABASE_PASS     role password; may be empty                 (optional)
 *   POS_EXPECTED_TARGET   identity the URL must resolve to            (required)
 *   PROVISION_CONFIRM     must be exactly "yes"                       (required)
 *
 * POS_EXPECTED_TARGET is what makes an absent identity impossible to satisfy:
 * a pooler hostname is shared by every project in a region, so the host alone
 * proves nothing. The expected target is compared against the pooler username
 * suffix (postgres.<project-ref>), which is the only per-project signal a JDBC
 * URL carries.
 *
 * No value here is ever printed: [describe] renders host-free, secret-free text.
 */
object ProvisionTarget {

    const val URL_ENV = "POS_DATABASE_URL"
    const val USER_ENV = "POS_DATABASE_USER"
    const val PASS_ENV = "POS_DATABASE_PASS"
    const val EXPECTED_ENV = "POS_EXPECTED_TARGET"
    /** Path to the Supabase root CA (prod-ca-2021.crt) the driver must pin. */
    const val ROOT_CERT_ENV = "POS_DATABASE_ROOT_CERT"
    const val CONFIRM_ENV = "PROVISION_CONFIRM"

    /** The only value of PROVISION_CONFIRM that proceeds. */
    const val CONFIRM_VALUE = "yes"

    /**
     * The only hostname shape a remote (Supabase) provisioning run may use.
     *
     * A Supabase pooler host is shared by every project in a region, so the host
     * identifies only the provider, never the project: it is necessary but not
     * sufficient. Anything else (a raw cluster host, a self-hosted database, an
     * IP literal) is refused rather than trusted.
     */
    private val SUPABASE_POOLER_HOST = Regex("^aws-[0-9]+-[a-z0-9-]+\\.pooler\\.supabase\\.com$")

    /** Ports the pooler serves: session (5432) and transaction (6543). */
    private val SUPABASE_POOLER_PORTS = setOf(5432, 6543)

    private val PROJECT_REF = Regex("^[a-z0-9]{20}$")
    private val POOLER_USER = Regex("^postgres\\.([a-z0-9]{20})$")
    private val DATABASE = Regex("^[a-z0-9_]+$")

    data class Target(
        val jdbcUrl: String,
        val user: String,
        val password: String,
        val expected: String,
        val host: String,
        val port: Int,
        val rootCertificatePath: String,
    ) {
        /** Identity line safe for logs: no password, no full URL. */
        fun describe(): String = "database=${databaseName()} role=${redactedRole()} endpoint=supabase-pooler"
        fun databaseName(): String = jdbcUrl.substringBefore('?').substringAfterLast('/')
        fun redactedRole(): String {
            val ref = POOLER_USER.find(user)?.groupValues?.get(1)
            return if (ref == null) "<local-role>" else "postgres.<project-ref>"
        }

        /** The project reference the CONFIGURATION claims. Not proof of anything. */
        fun configuredRef(): String? = POOLER_USER.find(user)?.groupValues?.get(1)
    }

    sealed class Outcome {
        data class Ok(val target: Target) : Outcome()
        data class Rejected(val code: String, val message: String) : Outcome()
    }

    /**
     * Validate the environment. Never throws for a rejected input and never
     * touches the network: the caller decides whether to connect at all.
     */
    fun resolve(env: Map<String, String> = System.getenv()): Outcome {
        val confirm = env[CONFIRM_ENV]
        if (confirm != CONFIRM_VALUE) {
            return Outcome.Rejected(
                "missing-confirmation",
                "Refusing to provision: $CONFIRM_ENV must be exactly '$CONFIRM_VALUE' " +
                    "(got ${if (confirm == null) "unset" else "a different value"}). " +
                    "Provisioning writes schema and is never implicit.",
            )
        }

        val rawUrl = env[URL_ENV]?.trim()
        if (rawUrl.isNullOrEmpty()) {
            return Outcome.Rejected(
                "missing-url",
                "Refusing to provision: $URL_ENV is not set. There is no default database " +
                    "target; supply the exact jdbc:postgresql:// URL to provision.",
            )
        }
        val urlProblem = validateUrl(rawUrl)
        if (urlProblem != null) {
            return Outcome.Rejected(urlProblem.first, urlProblem.second)
        }

        val expected = env[EXPECTED_ENV]?.trim()
        if (expected.isNullOrEmpty()) {
            return Outcome.Rejected(
                "missing-expected-target",
                "Refusing to provision: $EXPECTED_ENV is not set. The pooler host is shared " +
                    "by every project in the region, so provisioning requires the expected " +
                    "project identity (a 20-character Supabase project reference) to compare against.",
            )
        }
        if (!PROJECT_REF.matches(expected)) {
            return Outcome.Rejected(
                "malformed-expected-target",
                "Refusing to provision: $EXPECTED_ENV is not a valid Supabase project " +
                    "reference (expected 20 lowercase letters or digits).",
            )
        }

        val user = env[USER_ENV]?.trim()
        if (user.isNullOrEmpty()) {
            return Outcome.Rejected(
                "missing-user",
                "Refusing to provision: $USER_ENV is not set.",
            )
        }
        val pooler = POOLER_USER.find(user)
        if (pooler == null) {
            return Outcome.Rejected(
                "malformed-user",
                "Refusing to provision: $USER_ENV must be the pooler administrator form " +
                    "postgres.<project-ref> so the target project can be verified from the username.",
            )
        }
        if (pooler.groupValues[1] != expected) {
            return Outcome.Rejected(
                "target-mismatch",
                "Refusing to provision: the username names project <project-ref> but " +
                    "$EXPECTED_ENV designates <project-ref>. The two disagree.",
            )
        }

        val database = rawUrl.substringBefore('?').substringAfterLast('/')
        if (!DATABASE.matches(database) || database != "postgres") {
            return Outcome.Rejected(
                "unexpected-database",
                "Refusing to provision: the target database must be 'postgres', not '<other>'.",
            )
        }

        val endpoint = endpointOf(rawUrl)
            ?: return Outcome.Rejected("malformed-url", "Refusing to provision: $URL_ENV has no host.")
        val (host, port) = endpoint

        // The host must be a Supabase pooler endpoint. A matching username and
        // expected reference prove nothing if the endpoint is an arbitrary
        // server that happens to accept those strings.
        if (!SUPABASE_POOLER_HOST.matches(host)) {
            return Outcome.Rejected(
                "untrusted-endpoint",
                "Refusing to provision: the target host is not a Supabase pooler endpoint " +
                    "(expected aws-<n>-<region>.pooler.supabase.com). Remote provisioning never " +
                    "trusts an arbitrary or IP-literal host. For a local database use " +
                    "LOCAL_PROVISION_CONFIRM=yes with ./gradlew provisionLocalDatabase.",
            )
        }
        if (port !in SUPABASE_POOLER_PORTS) {
            return Outcome.Rejected(
                "untrusted-endpoint",
                "Refusing to provision: the target port is not a Supabase pooler port " +
                    "(expected 5432 or 6543).",
            )
        }

        // TLS policy. The distinction matters and is easy to get wrong:
        //   sslmode=require     encrypts, but does NOT verify the certificate or
        //                       the hostname -- a MITM with any certificate is
        //                       accepted, so the credential could be harvested.
        //   sslmode=verify-ca   verifies the CA chain, but NOT the hostname, so a
        //                       certificate for another host in the same chain
        //                       passes.
        //   sslmode=verify-full verifies the chain AND the hostname.
        // Remote provisioning sends an administrator credential, so only
        // verify-full is accepted. Anything else is rejected -- never downgraded.
        val query = rawUrl.substringAfter('?', "")
        val sslMode = Regex("(?:^|&)sslmode=([a-z-]+)", RegexOption.IGNORE_CASE)
            .find(query)?.groupValues?.get(1)?.lowercase()

        if (sslMode != "verify-full") {
            val seen = sslMode ?: "unset"
            return Outcome.Rejected(
                "tls-strong-verification-required",
                "Refusing to provision: $URL_ENV must set sslmode=verify-full " +
                    "(got '$seen'). sslmode=require encrypts without verifying the server " +
                    "certificate or hostname, so it cannot establish who we are talking to.",
            )
        }

        // A pinned trust root is required: verify-full against the system trust
        // store still accepts any publicly-trusted certificate for that hostname,
        // which is weaker than pinning the provider's CA.
        val rootCert = env[ROOT_CERT_ENV]?.trim()
        if (rootCert.isNullOrEmpty()) {
            return Outcome.Rejected(
                "missing-root-certificate",
                "Refusing to provision: $ROOT_CERT_ENV is not set. It must point to the " +
                    "Supabase root certificate (prod-ca-2021.crt, from the Dashboard: " +
                    "Database Settings -> SSL Configuration) that the driver pins.",
            )
        }
        if (!java.io.File(rootCert).isFile) {
            return Outcome.Rejected(
                "missing-root-certificate",
                "Refusing to provision: $ROOT_CERT_ENV does not name a readable file.",
            )
        }

        return Outcome.Ok(
            Target(
                jdbcUrl = rawUrl,
                user = user,
                password = env[PASS_ENV] ?: "",
                expected = expected,
                host = host,
                port = port,
                rootCertificatePath = rootCert,
            ),
        )
    }

    /**
     * Structural URL checks. Messages name the problem and never echo the value,
     * because a JDBC URL can carry a password in its userinfo component.
     */
    private fun validateUrl(raw: String): Pair<String, String>? {
        if (!raw.startsWith("jdbc:postgresql://")) {
            return "malformed-url" to
                "Refusing to provision: $URL_ENV must start with jdbc:postgresql:// ."
        }
        val rest = raw.removePrefix("jdbc:postgresql://")
        if (rest.contains('@')) {
            return "credentials-in-url" to
                "Refusing to provision: $URL_ENV must not embed credentials; " +
                    "use $USER_ENV and $PASS_ENV instead."
        }
        val hostPort = rest.substringBefore('/').substringBefore('?')
        if (hostPort.isEmpty() || !hostPort.contains('.')) {
            return "malformed-url" to
                "Refusing to provision: $URL_ENV must name a fully qualified host."
        }
        if (raw.substringAfter('?', "").contains("password", ignoreCase = true)) {
            return "credentials-in-url" to
                "Refusing to provision: $URL_ENV must not carry a password parameter; " +
                    "use $PASS_ENV instead."
        }
        val uri = runCatching { URI(raw.removePrefix("jdbc:")) }.getOrNull()
        if (uri == null || !uri.scheme.equals("postgresql", ignoreCase = true)) {
            return "malformed-url" to "Refusing to provision: $URL_ENV could not be parsed."
        }
        return null
    }

    /** host[:port] of the URL, empty when it cannot be parsed. */
    private fun endpointOf(raw: String): Pair<String, Int>? {
        val rest = raw.removePrefix("jdbc:postgresql://")
        val hostPort = rest.substringBefore('/').substringBefore('?')
        if (hostPort.isEmpty()) return null
        val host = hostPort.substringBefore(':').lowercase()
        val port = hostPort.substringAfter(':', "5432").toIntOrNull() ?: return null
        return host to port
    }
}