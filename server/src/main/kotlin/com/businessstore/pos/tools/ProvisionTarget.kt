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
    const val CONFIRM_ENV = "PROVISION_CONFIRM"

    /** The only value of PROVISION_CONFIRM that proceeds. */
    const val CONFIRM_VALUE = "yes"

    private val PROJECT_REF = Regex("^[a-z0-9]{20}$")
    private val POOLER_USER = Regex("^postgres\\.([a-z0-9]{20})$")
    private val DATABASE = Regex("^[a-z0-9_]+$")

    data class Target(val jdbcUrl: String, val user: String, val password: String, val expected: String) {
        /** Identity line safe for logs: no password, no full URL. */
        fun describe(): String = "database=${databaseName()} role=${redactedRole()}"
        fun databaseName(): String = jdbcUrl.substringBefore('?').substringAfterLast('/')
        fun redactedRole(): String {
            val ref = POOLER_USER.find(user)?.groupValues?.get(1)
            return if (ref == null) "<local-role>" else "postgres.<project-ref>"
        }
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

        return Outcome.Ok(
            Target(
                jdbcUrl = rawUrl,
                user = user,
                password = env[PASS_ENV] ?: "",
                expected = expected,
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
}