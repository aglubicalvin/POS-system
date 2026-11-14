package com.businessstore.pos.config

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Base64

/**
 * Combined staging target configuration shared by the server's staging paths.
 *
 * A staging target is fully specified by four independent environment values,
 * never by inference from a project reference or from a previously used endpoint:
 *
 *   POS_STAGING_PROJECT_REF         --- the human-confirmed project reference
 *   POS_STAGING_ADMIN_USER          --- postgres.<project-ref>, matching that reference
 *   POS_STAGING_POOLER_HOST         --- validated pooler hostname (StagingPoolerHost)
 *   POS_STAGING_DB_ROOT_CERT        --- staging root cert PEM, read from the filesystem
 *   POS_STAGING_DB_ROOT_CERT_SHA256 --- SHA-256 of that cert's DER encoding
 *
 * Fail-closed contract:
 *
 *   - Missing or malformed pooler host rejects immediately.
 *   - Missing or malformed staged cert / fingerprint rejects immediately.
 *   - A fingerprint that does not equal sha256(DER(cert)) rejects immediately.
 *   - Any other error rejects immediately with a reason that never prints secrets.
 *
 * TLS is verify-full only. There is no prefer/require/allow/disable fallback, no
 * printed certificate contents, and no interception hook that could replace the
 * trust material.
 */
object StagingConnectionConfig {

    const val PROJECT_REF_ENV = "POS_STAGING_PROJECT_REF"
    const val ADMIN_USER_ENV = "POS_STAGING_ADMIN_USER"
    const val POOLER_HOST_ENV = "POS_STAGING_POOLER_HOST"
    const val ROOT_CERT_ENV = "POS_STAGING_DB_ROOT_CERT"
    const val ROOT_CERT_SHA256_ENV = "POS_STAGING_DB_ROOT_CERT_SHA256"
    const val NON_STAGING_REFS_ENV = "POS_NON_STAGING_REFS"

    /**
     * References that are known NOT to be staging: the documented development
     * project and the former verification project. Immutable.
     * POS_NON_STAGING_REFS adds to this list and can never remove an entry.
     */
    val KNOWN_NON_STAGING_REFS: List<String> = listOf(
        "uemdrtrzlvhdtonccczt",
        "numwzjogaanfhlrfgrqk",
    )

    const val DATABASE = "postgres"

    /** The only accepted TLS mode for staging. There is no fallback. */
    const val SSL_MODE = "verify-full"

    private val PROJECT_REF = Regex("^[a-z0-9]{20}$")
    private val ADMIN_USER = Regex("""^postgres\.([a-z0-9]{20})$""")
    private val FINGERPRINT = Regex("^[a-f0-9]{64}$")

    /** Thrown by require when the staging configuration is unusable. */
    class StagingConnectionError(
        val code: String,
        message: String,
    ) : IllegalArgumentException(message)

    data class Validated(
        val projectRef: String,
        val adminUser: String,
        val database: String,
        val poolerHost: StagingPoolerHost.Validated,
        /**
         * Private copy holding exactly the bytes whose fingerprint was
         * verified. The connection uses this path, never the caller's
         * source path, which could have changed after verification.
         */
        val certPath: Path,
        /** The configured source path, kept for diagnostics only. */
        val sourceCertPath: Path,
        val rootCertSha256: String,
        val sslMode: String = SSL_MODE,
    ) {
        /**
         * libpq connection string built from the validated contract only: no
         * hardcoded host, no credentials, verify-full TLS with the staged cert.
         */
        fun connectionString(): String =
            "host=${poolerHost.host} port=5432 dbname=$database " +
                "sslmode=$sslMode sslrootcert=$certPath"
    }

    sealed class Outcome {
        data class Ok(val config: Validated) : Outcome()
        data class Rejected(val code: String, val message: String) : Outcome()
    }

    /**
     * Resolve the whole staging connection contract from [env], or explain why it
     * cannot be used. Fields are validated in a fixed order so the first missing or
     * malformed value is the one reported.
     */
    fun resolve(env: Map<String, String> = System.getenv()): Outcome {
        val projectRef = env[PROJECT_REF_ENV]?.trim().orEmpty()
        if (projectRef.isEmpty()) {
            return rejected(
                "missing-ref",
                "$PROJECT_REF_ENV has not been explicitly configured. The staging project " +
                    "must be designated by the project owner before any connection.",
            )
        }
        if (!PROJECT_REF.matches(projectRef)) {
            return rejected(
                "malformed-ref",
                "$PROJECT_REF_ENV is not a valid Supabase project reference " +
                    "(expected 20 lowercase letters or digits).",
            )
        }

        // POS_NON_STAGING_REFS is ADDITIVE: the built-ins are immutable, so a
        // blank or empty custom value can never remove them. A malformed entry
        // is refused rather than ignored. Mirrors the TypeScript, Java and
        // PowerShell paths.
        val customNonStaging = env[NON_STAGING_REFS_ENV]
            ?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (customNonStaging.any { !PROJECT_REF.matches(it) }) {
            return rejected(
                "malformed-non-staging-ref",
                "$NON_STAGING_REFS_ENV contains an entry that is not a Supabase " +
                    "project reference. Refusing to guess which projects are non-staging.",
            )
        }
        if ((KNOWN_NON_STAGING_REFS + customNonStaging).toSet().contains(projectRef)) {
            return rejected(
                "non-staging-ref",
                "$projectRef is configured as a known non-staging project. " +
                    "Set a dedicated staging reference.",
            )
        }

        val adminUser = env[ADMIN_USER_ENV]?.trim().orEmpty()
        if (adminUser.isEmpty()) {
            return rejected(
                "missing-admin-user",
                "$ADMIN_USER_ENV has not been configured. Expected postgres.$projectRef.",
            )
        }
        val suffix = ADMIN_USER.matchEntire(adminUser)?.groupValues?.get(1)
        if (suffix == null) {
            return rejected(
                "malformed-admin-user",
                "$ADMIN_USER_ENV is malformed. Expected the pooler administrator form " +
                    "postgres.<project-ref>.",
            )
        }
        if (suffix != projectRef) {
            return rejected(
                "ref-mismatch",
                "The administrator username belongs to project $suffix but " +
                    "$PROJECT_REF_ENV designates $projectRef. Refusing to continue.",
            )
        }

        val poolerHost = when (val outcome = StagingPoolerHost.resolve(env)) {
            is StagingPoolerHost.Outcome.Ok -> outcome.validated
            is StagingPoolerHost.Outcome.Rejected -> return rejected(outcome.code, outcome.message)
        }

        val certPathRaw = env[ROOT_CERT_ENV]?.trim().orEmpty()
        if (certPathRaw.isEmpty()) {
            return rejected(
                "missing-staging-root-certificate",
                "$ROOT_CERT_ENV has not been explicitly configured. A staged root certificate " +
                    "is mandatory: there is no default and no unverified fallback.",
            )
        }

        val suppliedSha256 = env[ROOT_CERT_SHA256_ENV]
            ?.trim()
            ?.replace(":", "")
            ?.lowercase()
            .orEmpty()
        if (suppliedSha256.isEmpty()) {
            return rejected(
                "missing-staging-root-certificate-fingerprint",
                "$ROOT_CERT_SHA256_ENV has not been explicitly configured. A staged " +
                    "certificate without an independently supplied fingerprint would accept " +
                    "any CA the caller names.",
            )
        }
        if (!FINGERPRINT.matches(suppliedSha256)) {
            return rejected(
                "malformed-staging-root-certificate-fingerprint",
                "$ROOT_CERT_SHA256_ENV is not a 64-character hex SHA-256 digest. Recompute it " +
                    "over the DER encoding of $ROOT_CERT_ENV.",
            )
        }

        val sourceCertPath = Path.of(certPathRaw).toAbsolutePath().normalize()
        val pemBytes = readCertBytes(sourceCertPath)
            ?: return rejected(
                "missing-staging-root-certificate",
                "The staged root certificate named by $ROOT_CERT_ENV is not a readable file.",
            )
        val der = decodeDer(pemBytes)
            ?: return rejected(
                "missing-staging-root-certificate",
                "The staged root certificate named by $ROOT_CERT_ENV is not a readable PEM certificate.",
            )
        val actualSha256 = sha256Hex(der)
        if (actualSha256 != suppliedSha256) {
            return rejected(
                "root-certificate-fingerprint-mismatch",
                "The SHA-256 of the staged certificate does not match " +
                    "$ROOT_CERT_SHA256_ENV. The certificate may have been rotated, or this is " +
                    "not the approved staging target.",
            )
        }

        // Consume the verified byte sequence, not the path it came from: a file
        // replaced between hashing and connecting would otherwise be trusted
        // from one sequence and used from another.
        val verifiedCert = materializeVerifiedCert(pemBytes)
        return Outcome.Ok(
            Validated(
                projectRef = projectRef,
                adminUser = adminUser,
                database = DATABASE,
                poolerHost = poolerHost,
                certPath = verifiedCert,
                sourceCertPath = sourceCertPath,
                rootCertSha256 = actualSha256,
            ),
        )
    }

    /** Throwing form for code paths that must stop rather than branch. */
    fun require(env: Map<String, String> = System.getenv()): Validated =
        when (val outcome = resolve(env)) {
            is Outcome.Ok -> outcome.config
            is Outcome.Rejected -> throw StagingConnectionError(outcome.code, outcome.message)
        }

    private fun rejected(code: String, message: String): Outcome.Rejected =
        Outcome.Rejected(code = code, message = "BLOCKED: $message")

    /** The certificate bytes, read once. */
    private fun readCertBytes(path: Path): ByteArray? =
        try {
            if (Files.isRegularFile(path)) Files.readAllBytes(path) else null
        } catch (_: IOException) {
            null
        }

    /** DER from a PEM byte sequence, or null when it cannot be decoded. */
    private fun decodeDer(pemBytes: ByteArray): ByteArray? {
        val base64 = String(pemBytes, Charsets.US_ASCII)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("-----") }
            .joinToString("")
        return try {
            Base64.getDecoder().decode(base64)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** SHA-256 over DER, matching the provisioning contract. */
    private fun sha256Hex(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(der)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * Write the verified bytes to private temp material and return it. The
     * connection uses this copy, so the fingerprint that was checked is the
     * certificate that is presented. Deleted at JVM exit.
     */
    private fun materializeVerifiedCert(pemBytes: ByteArray): Path {
        val temp = Files.createTempFile("staging-root-cert-", ".pem")
        Files.write(temp, pemBytes)
        try {
            Files.setPosixFilePermissions(temp, setOf(PosixFilePermission.OWNER_READ))
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem (e.g. Windows): the temp file's ACLs apply.
        }
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { Files.deleteIfExists(temp) } })
        return temp
    }
}
