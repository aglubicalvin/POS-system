package com.businessstore.pos.config

/**
 * Server configuration from the environment.
 *
 * Database configuration is REQUIRED and has no default. The previous version
 * silently fell back to `jdbc:postgresql://localhost:5432/business_store` when
 * `POS_DATABASE_URL` was unset, so a hosted deployment with a missing variable
 * would start against a local database instead of failing. A hosted service must
 * fail closed.
 *
 * Local development is still supported, but it is now a DELIBERATE choice: set
 * `POS_LOCAL_DEV=yes` and the localhost defaults apply. That flag is refused when
 * the process looks hosted (see [fromEnv]), so it cannot be left on by accident in
 * a deployed environment.
 *
 * `enrollmentSecret`, when set, gates device registration so a stolen till cannot
 * enrol itself. Leaving it unset is only appropriate in tests and local dev; the
 * startup log warns about it.
 */
data class ServerConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val port: Int,
    val enrollmentSecret: String?,
    /**
     * HMAC secret the mobile-money provider signs its callbacks with. Unset means
     * the webhook stays disabled (503) rather than accepting unsigned callbacks.
     */
    val momoWebhookSecret: String? = null,
    /** Provider/aggregator name recorded on the payment row, e.g. "mtn-momo". */
    val momoProvider: String? = null,
) {
    companion object {
        const val LOCAL_DEV_ENV = "POS_LOCAL_DEV"
        const val DATABASE_URL_ENV = "POS_DATABASE_URL"
        const val DATABASE_USER_ENV = "POS_DATABASE_USER"
        const val DATABASE_PASSWORD_ENV = "POS_DATABASE_PASSWORD"

        /** The only values of POS_LOCAL_DEV that enable the local defaults. */
        private val LOCAL_DEV_VALUES = setOf("yes", "true")

        /** Defaults used ONLY when local development is explicitly requested. */
        const val LOCAL_JDBC_URL = "jdbc:postgresql://localhost:5432/business_store"
        const val LOCAL_USER = "business_store"

        /**
         * Environment variables that indicate a hosted runtime. If any is present,
         * POS_LOCAL_DEV is refused: a deployed process must never quietly use a
         * localhost database.
         */
        private val HOSTED_MARKERS = listOf(
            "DYNO",              // Heroku
            "FLY_APP_NAME",      // Fly.io
            "RAILWAY_ENVIRONMENT", // Railway
            "RENDER",            // Render
            "K_SERVICE",         // Cloud Run
            "WEBSITE_SITE_NAME", // Azure App Service
            "KUBERNETES_SERVICE_HOST",
        )

        /**
         * Resolve configuration, or throw [IllegalStateException] with an
         * actionable message. Never returns a configuration that silently points at
         * a database the operator did not name.
         */
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            val localDev = env[LOCAL_DEV_ENV]?.trim()?.lowercase() in LOCAL_DEV_VALUES
            val hostedMarker = HOSTED_MARKERS.firstOrNull { !env[it].isNullOrBlank() }

            if (localDev && hostedMarker != null) {
                throw IllegalStateException(
                    "CONFIG_INVALID: $LOCAL_DEV_ENV is set but this process looks hosted " +
                        "($hostedMarker is present). Local development defaults are refused in a " +
                        "deployed environment; set $DATABASE_URL_ENV instead.",
                )
            }

            val rawUrl = env[DATABASE_URL_ENV]?.trim()
            val jdbcUrl = when {
                !rawUrl.isNullOrEmpty() -> rawUrl
                localDev -> LOCAL_JDBC_URL
                else -> throw IllegalStateException(
                    "CONFIG_INVALID: $DATABASE_URL_ENV is not set. There is no default database " +
                        "target. For local development set $LOCAL_DEV_ENV=yes; for a hosted " +
                        "deployment set $DATABASE_URL_ENV to the full jdbc:postgresql:// URL.",
                )
            }

            if (!jdbcUrl.startsWith("jdbc:postgresql://")) {
                throw IllegalStateException(
                    "CONFIG_INVALID: $DATABASE_URL_ENV must start with jdbc:postgresql:// .",
                )
            }
            val rest = jdbcUrl.removePrefix("jdbc:postgresql://")
            if (rest.substringBefore('/').substringBefore('?').isEmpty() ||
                !rest.contains('/') ||
                rest.substringAfter('/').substringBefore('?').isEmpty()
            ) {
                throw IllegalStateException(
                    "CONFIG_INVALID: $DATABASE_URL_ENV must name a host and a database.",
                )
            }

            val username = env[DATABASE_USER_ENV]?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (localDev) LOCAL_USER else throw IllegalStateException(
                    "CONFIG_INVALID: $DATABASE_USER_ENV is not set.",
                )

            return ServerConfig(
                jdbcUrl = jdbcUrl,
                username = username,
                password = env[DATABASE_PASSWORD_ENV] ?: "",
                port = env["POS_PORT"]?.toIntOrNull() ?: 8080,
                enrollmentSecret = env["POS_ENROLLMENT_SECRET"]?.takeIf { it.isNotBlank() },
                momoWebhookSecret = env["MOMO_WEBHOOK_SECRET"]?.takeIf { it.isNotBlank() },
                momoProvider = env["MOMO_PROVIDER"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}
