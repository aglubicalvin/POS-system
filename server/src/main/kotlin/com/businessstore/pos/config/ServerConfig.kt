package com.businessstore.pos.config

/**
 * Server configuration from the environment.
 *
 * `enrollmentSecret`, when set, gates device registration so a stolen till cannot
 * enrol itself. Leaving it unset is only appropriate in tests and local dev — the
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
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig = ServerConfig(
            jdbcUrl = env["POS_DATABASE_URL"] ?: "jdbc:postgresql://localhost:5432/business_store",
            username = env["POS_DATABASE_USER"] ?: "business_store",
            password = env["POS_DATABASE_PASSWORD"] ?: "",
            port = env["POS_PORT"]?.toIntOrNull() ?: 8080,
            enrollmentSecret = env["POS_ENROLLMENT_SECRET"]?.takeIf { it.isNotBlank() },
            momoWebhookSecret = env["MOMO_WEBHOOK_SECRET"]?.takeIf { it.isNotBlank() },
            momoProvider = env["MOMO_PROVIDER"]?.takeIf { it.isNotBlank() },
        )
    }
}
