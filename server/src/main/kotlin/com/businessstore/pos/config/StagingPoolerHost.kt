package com.businessstore.pos.config

import java.util.regex.Pattern

/**
 * One shared staging pooler-host contract, used by all staging connection paths.
 *
 * Both the JDBC identity-probe path (ProbeState via execProbeState) and the
 * PowerShell/libpq role-creation path (probe-readonly.ps1 via docker psql) consume
 * the SAME explicitly configured pooler hostname:
 *
 *   POS_STAGING_POOLER_HOST
 *
 * The contract mirrors the TypeScript staging-target module's
 * SUPABASE_POOLER_HOST_PATTERN and resolveStagingPoolerHost, and the validation in
 * probe-readonly.ps1, so all three paths enforce identical rules and accept
 * identical inputs.
 *
 * Accepted shape:
 *
 *   aws-<n>-<region>.pooler.supabase.com
 *
 * where <n> is one or more digits and <region> is one or more lowercase letters,
 * digits or hyphens. The entire hostname is anchored to the Supabase pooler DNS
 * domain, so arbitrary DNS names, attacker-controlled suffixes, IP literals,
 * loopback names, URIs, paths, credentials, query strings, ports and whitespace
 * are all refused.
 *
 * The value is normalised (trimmed, lowercased) before validation, exactly like the
 * TypeScript path. The raw environment value is never passed onward.
 *
 * There is no default, no previously used region, and no fallback inferred from a
 * project reference or from a previously used endpoint. The project region must be
 * confirmed by a human against the staging project's Supabase dashboard before any
 * connection is attempted.
 *
 * This does NOT establish project identity. The pooler hostname is shared by every
 * project in a region and identifies only the provider. Project identity is still
 * established by the administrator username suffix postgres.<POS_STAGING_PROJECT_REF>
 * compared against the independently supplied reference.
 */
object StagingPoolerHost {

    const val ENV = "POS_STAGING_POOLER_HOST"

    private val PATTERN: Pattern = Pattern.compile(
        """^aws-([0-9]+)-([a-z0-9-]+)\.pooler\.supabase\.com$""",
        Pattern.CASE_INSENSITIVE,
    )

    /** Thrown by require when the configuration is missing or malformed. */
    class StagingPoolerHostError(
        val code: String,
        message: String,
    ) : IllegalArgumentException(message)

    data class Validated(
        /** Normalised, validated hostname; the only value any connection path may use. */
        val host: String,
        /** The <n> component of the hostname. */
        val n: String,
        /** The <region> component of the hostname. */
        val region: String,
    ) {
        /** Redacted form safe for logs and reports. */
        fun describe(): String = "pooler=aws-$n-$region.pooler.supabase.com"
    }

    sealed class Outcome {
        data class Ok(val validated: Validated) : Outcome()
        data class Rejected(
            val code: String,
            /** Message safe for logs: never the raw hostname or any credential. */
            val message: String,
        ) : Outcome()
    }

    /**
     * Resolve and validate the staging pooler hostname from [env], or explain why it
     * cannot. Like the TypeScript implementation, this normalises (trim + lowercase)
     * before matching and never passes the raw value onward.
     */
    fun resolve(env: Map<String, String> = System.getenv()): Outcome {
        val raw = env[ENV]
        if (raw == null || raw.isBlank()) {
            return Outcome.Rejected(
                code = "missing-staging-pooler-host",
                message = "BLOCKED: $ENV is not set. " +
                    "The staging connection must use an explicitly configured Supabase pooler " +
                    "hostname. There is no default, no previously used region, and no fallback: " +
                    "the pooler hostname is shared by every project in a region and identifies " +
                    "only the provider, never the project, so the project identity is still " +
                    "established by the administrator username suffix compared against " +
                    "POS_STAGING_PROJECT_REF.",
            )
        }

        val normalised = raw.trim().lowercase()
        val matcher = PATTERN.matcher(normalised)
        if (!matcher.matches()) {
            return Outcome.Rejected(
                code = "malformed-staging-pooler-host",
                message = "BLOCKED: $ENV does not name a Supabase pooler endpoint. " +
                    "Expected exactly aws-<n>-<region>.pooler.supabase.com " +
                    "(for example aws-0-eu-west-2.pooler.supabase.com), with <n> one or more " +
                    "digits and <region> one or more lowercase letters, digits or hyphens. " +
                    "IP literals, loopback names, arbitrary DNS names, URIs, paths, " +
                    "credentials, query strings and ports are refused. The project region must " +
                    "be confirmed by a human against the staging project's Supabase dashboard " +
                    "before any connection is attempted; it is never inferred from the project " +
                    "reference or from any previously used endpoint.",
            )
        }

        val n = matcher.group(1)
        val region = matcher.group(2)
        val host = "aws-$n-$region.pooler.supabase.com"

        // Rebuild the validated form from the captured groups so it is exactly the
        // canonical lower-case hostname, never a casing variant of the input.
        return Outcome.Ok(Validated(host = host, n = n, region = region))
    }

    /** Throwing form for code paths that must stop rather than branch. */
    fun require(env: Map<String, String> = System.getenv()): Validated =
        when (val outcome = resolve(env)) {
            is Outcome.Ok -> outcome.validated
            is Outcome.Rejected -> throw StagingPoolerHostError(outcome.code, outcome.message)
        }
}
