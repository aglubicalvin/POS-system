package com.businessstore.pos.tools

/**
 * The exact LOCAL database a local provisioning run may touch.
 *
 * This is deliberately a separate path from [ProvisionTarget]: that one targets a
 * remote Supabase project and requires a pooler login plus a project reference,
 * none of which exist on a developer laptop. Conflating the two would either
 * weaken the remote checks or make local development impossible.
 *
 * Both paths end in the same transactional [com.businessstore.pos.db.Schema.apply]
 * and neither applies migrations 0001-0009.
 *
 * Env contract (nothing else is read):
 *   LOCAL_PROVISION_CONFIRM  must be exactly "yes"                          (required)
 *   POS_LOCAL_DATABASE_URL   jdbc:postgresql://<loopback>:<port>/<database> (required)
 *   POS_LOCAL_DATABASE_USER  the local role                                 (required)
 *   POS_LOCAL_DATABASE_PASS  local role password; may be empty              (optional)
 *
 * Loopback only. A remote host is refused, so a local confirmation can never be
 * used to reach a shared server.
 */
object LocalProvisionTarget {

    const val CONFIRM_ENV = "LOCAL_PROVISION_CONFIRM"
    const val URL_ENV = "POS_LOCAL_DATABASE_URL"
    const val USER_ENV = "POS_LOCAL_DATABASE_USER"
    const val PASS_ENV = "POS_LOCAL_DATABASE_PASS"
    const val CONFIRM_VALUE = "yes"

    private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1", "[::1]")
    private val DATABASE = Regex("^[a-z0-9_]+$")

    data class Target(val jdbcUrl: String, val user: String, val password: String, val host: String, val database: String) {
        /** Safe for logs: loopback host, database name, no password. */
        fun describe(): String = "database=$database role=<local-role> endpoint=loopback"
    }

    sealed class Outcome {
        data class Ok(val target: Target) : Outcome()
        data class Rejected(val code: String, val message: String) : Outcome()
    }

    fun resolve(env: Map<String, String> = System.getenv()): Outcome {
        if (env[CONFIRM_ENV] != CONFIRM_VALUE) {
            return Outcome.Rejected(
                "missing-confirmation",
                "Refusing to provision a local database: $CONFIRM_ENV must be exactly '$CONFIRM_VALUE'. " +
                    "Provisioning writes schema and is never implicit.",
            )
        }

        val rawUrl = env[URL_ENV]?.trim()
        if (rawUrl.isNullOrEmpty()) {
            return Outcome.Rejected(
                "missing-url",
                "Refusing to provision a local database: $URL_ENV is not set. There is no default target.",
            )
        }
        if (!rawUrl.startsWith("jdbc:postgresql://")) {
            return Outcome.Rejected(
                "malformed-url",
                "Refusing to provision a local database: $URL_ENV must start with jdbc:postgresql:// .",
            )
        }
        if (rawUrl.substringBefore('?').contains('@')) {
            return Outcome.Rejected(
                "credentials-in-url",
                "Refusing to provision a local database: $URL_ENV must not embed credentials; " +
                    "use $USER_ENV and $PASS_ENV.",
            )
        }

        // No query parameters at all on this path. A local URL has nothing to
        // configure through them, and accepting arbitrary keys would let a caller
        // inject driver options (including SSL factories) just as the remote path
        // could before that was closed off.
        if (rawUrl.substringAfter('?', "").isNotBlank()) {
            return Outcome.Rejected(
                "unsupported-query-parameter",
                "Refusing to provision a local database: $URL_ENV must not carry query " +
                    "parameters. Only host, port and database name are used.",
            )
        }

        val rest = rawUrl.removePrefix("jdbc:postgresql://")

        // pgJDBC accepts a comma-separated authority and fails over to the next
        // host when the first is unreachable, so "localhost:5432,evil.example.test"
        // would connect to a remote server while looking loopback-only. The
        // authority must therefore name exactly one host.
        val authority = rest.substringBefore('/').substringBefore('?')
        if (authority.contains(',')) {
            return Outcome.Rejected(
                "multiple-hosts",
                "Refusing to provision a local database: $URL_ENV names more than one host. " +
                    "A single loopback host is required; the driver would otherwise fail over " +
                    "to the other hosts.",
            )
        }
        val hostPort = rest.substringBefore('/').substringBefore('?')
        if (hostPort.isEmpty()) {
            return Outcome.Rejected("malformed-url", "Refusing to provision a local database: $URL_ENV has no host.")
        }
        // Bracketed IPv6 ("[::1]:5432") must have its brackets removed BEFORE
        // splitting on ':': "[::1]" contains colons, so substringBefore(':') would
        // return "[" alone.
        val host = if (hostPort.startsWith("[")) {
            hostPort.substringAfter('[').substringBefore(']')
        } else {
            hostPort.substringBefore(':')
        }.lowercase()
        if (host !in LOOPBACK) {
            return Outcome.Rejected(
                "non-loopback-host",
                "Refusing to provision a local database: the host is not loopback " +
                    "(localhost, 127.0.0.1 or ::1). For a remote or Supabase target use " +
                    "./gradlew provisionDatabase with PROVISION_CONFIRM=yes instead.",
            )
        }

        val database = rest.substringBefore('?').substringAfterLast('/')
        if (!DATABASE.matches(database)) {
            return Outcome.Rejected(
                "missing-database-name",
                "Refusing to provision a local database: $URL_ENV must name a database.",
            )
        }

        val user = env[USER_ENV]?.trim()
        if (user.isNullOrEmpty()) {
            return Outcome.Rejected("missing-user", "Refusing to provision a local database: $USER_ENV is not set.")
        }

        return Outcome.Ok(
            Target(
                jdbcUrl = rawUrl,
                user = user,
                password = env[PASS_ENV] ?: "",
                host = host,
                database = database,
            ),
        )
    }
}