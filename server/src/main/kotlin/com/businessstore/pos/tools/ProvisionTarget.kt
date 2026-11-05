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

    /**
     * SHA-256 fingerprint the certificate file must match, supplied from a source
     * OTHER than the file itself (the value published by Supabase in the Dashboard
     * and recorded by the release owner). Validating only that the path is readable
     * would accept any CA a caller names, so verify-full would prove only that the
     * peer chains to whatever that file contains.
     */
    const val ROOT_CERT_SHA256_ENV = "POS_DATABASE_ROOT_CERT_SHA256"
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

    /**
     * A fully validated remote target.
     *
     * [jdbcUrl] is the ORIGINAL input, kept only for logging-free reference; the
     * connector is built from [host]/[port]/[database] and the explicit SSL
     * settings, never by handing the raw URL to the driver. That is deliberate:
     * pgJDBC's BaseDataSource.setUrl() copies every URL parameter into the
     * property map, so a URL containing sslfactory=NonValidatingFactory would
     * install a trust-all TrustManager and defeat sslmode=verify-full.
     */
    data class Target(
        val jdbcUrl: String,
        val user: String,
        val password: String,
        val expected: String,
        val host: String,
        val port: Int,
        val database: String,
        /**
         * The verified certificate itself, as PEM, re-encoded from the DER that was
         * hashed. This -- not a path -- is the trust root of record: the driver is
         * given a private copy of THESE bytes (see PinnedTrustMaterial), so a later
         * write to the file the caller named cannot change what is trusted.
         */
        val rootCertificatePem: String,
        /** SHA-256 of the same certificate's DER encoding, for re-verification. */
        val rootCertificateSha256: String,
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
        // Reject unsafe, unknown or duplicated query parameters BEFORE looking at
        // sslmode. Checking only the first sslmode occurrence (as this code used
        // to) let `sslmode=verify-full&sslmode=require` through, and passing the
        // raw URL to the driver let sslfactory=NonValidatingFactory install a
        // trust-all TrustManager.
        val queryProblem = validateQuery(rawUrl)
        if (queryProblem != null) {
            return Outcome.Rejected(queryProblem.first, queryProblem.second)
        }

        // Exactly one sslmode, and it must be verify-full.
        val sslModes = Regex("(?:^|&)sslmode=([^&]*)", RegexOption.IGNORE_CASE)
            .findAll(rawUrl.substringAfter('?', ""))
            .map { it.groupValues[1].trim().lowercase() }
            .toList()
        if (sslModes.size > 1) {
            return Outcome.Rejected(
                "duplicate-sslmode",
                "Refusing to provision: $URL_ENV sets sslmode more than once; " +
                    "exactly one value (verify-full) is required.",
            )
        }
        val sslMode = sslModes.singleOrNull()

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
        val certFile = java.io.File(rootCert)
        if (!certFile.isFile) {
            return Outcome.Rejected(
                "missing-root-certificate",
                "Refusing to provision: $ROOT_CERT_ENV does not name an existing file.",
            )
        }
        // Read the certificate material EXACTLY ONCE, into a byte snapshot. Every
        // later check -- parsing, the digest, and the private copy handed to the
        // driver -- is derived from these bytes, never from reopening this path:
        // re-reading it would reintroduce the window between verification and use.
        // isFile alone says nothing about permissions either, so the read is the
        // proof that this process can use the file at all.
        val snapshot = try {
            java.nio.file.Files.readAllBytes(certFile.toPath())
        } catch (error: java.io.IOException) {
            return Outcome.Rejected(
                "unreadable-root-certificate",
                "Refusing to provision: $ROOT_CERT_ENV names a file this process cannot read.",
            )
        } catch (error: SecurityException) {
            return Outcome.Rejected(
                "unreadable-root-certificate",
                "Refusing to provision: $ROOT_CERT_ENV names a file this process cannot read.",
            )
        }

        // A readable file is not a trusted CA. Without an independent expectation
        // the operator (or anything that can set the environment) could point this
        // at an attacker-chosen root whose key signs a certificate for the pooler
        // hostname, and verify-full would accept the interception.
        val expectedFingerprint = env[ROOT_CERT_SHA256_ENV]?.trim()?.replace(":", "")?.lowercase()
        if (expectedFingerprint.isNullOrEmpty()) {
            return Outcome.Rejected(
                "missing-root-certificate-fingerprint",
                "Refusing to provision: $ROOT_CERT_SHA256_ENV is not set. It must contain the " +
                    "SHA-256 fingerprint of the Supabase root certificate, taken from an " +
                    "independent source, so the file cannot be swapped for another CA.",
            )
        }
        if (!expectedFingerprint.matches(Regex("^[a-f0-9]{64}$"))) {
            return Outcome.Rejected(
                "malformed-root-certificate-fingerprint",
                "Refusing to provision: $ROOT_CERT_SHA256_ENV must be a 64-character hex SHA-256 digest.",
            )
        }
        val verified = when (val root = readRootCertificate(snapshot)) {
            is RootCertificate.Pinned -> root
            // verify-full trusts EVERY certificate in this file, so hashing only the
            // first one would accept a root whose file also carries another CA.
            RootCertificate.MoreThanOne -> return Outcome.Rejected(
                "multiple-root-certificates",
                "Refusing to provision: $ROOT_CERT_ENV contains more than one certificate. " +
                    "The driver trusts every certificate in the file, so exactly one root -- " +
                    "the one matching $ROOT_CERT_SHA256_ENV -- is accepted.",
            )
            RootCertificate.Unparseable -> return Outcome.Rejected(
                "malformed-root-certificate",
                "Refusing to provision: $ROOT_CERT_ENV is not a readable PEM certificate.",
            )
        }
        if (verified.sha256 != expectedFingerprint) {
            return Outcome.Rejected(
                "root-certificate-mismatch",
                "Refusing to provision: the certificate in $ROOT_CERT_ENV does not match " +
                    "$ROOT_CERT_SHA256_ENV. The trust root is not the expected one.",
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
                database = database,
                rootCertificatePem = verified.pem,
                rootCertificateSha256 = verified.sha256,
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
        // pgJDBC accepts comma-separated hosts and fails over to them, so a URL
        // whose first host is a valid pooler could still connect elsewhere.
        // Exactly one endpoint is allowed.
        if (hostPort.contains(',')) {
            return "multiple-hosts" to
                "Refusing to provision: $URL_ENV names more than one host. Exactly one " +
                    "Supabase pooler endpoint is required."
        }
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

    /**
     * Query parameters that are allowed to appear in POS_DATABASE_URL.
     *
     * The list is short on purpose. Every pgJDBC parameter that can replace the
     * SSL factory, the hostname verifier or the trust root is REJECTED rather
     * than stripped: silently removing one would leave the operator believing a
     * setting took effect. sslmode is allowed here only so it can be required;
     * its value is validated exactly and the driver is configured from the
     * validated fields, not from the URL.
     */
    private val ALLOWED_QUERY_PARAMS = setOf("sslmode")

    /**
     * Parameters that can weaken certificate or hostname verification. Named
     * explicitly so the rejection message can say why, instead of "unknown key".
     */
    private val FORBIDDEN_QUERY_PARAMS = setOf(
        "sslfactory",
        "sslfactoryarg",
        "sslhostnameverifier",
        "sslrootcert",
        "sslcert",
        "sslkey",
        "sslpassword",
        "sslcertmode",
        "sslnegotiation",
        "ssltunnel",
        "sslresponse",
        "sslresponsefile",
        "ssl",          // alias that can imply a non-validating negotiation
        "channelbinding",
        "options",
        "socketfactory",
        "socketfactoryarg",
        "loggerfile",
        "loggerlevel",
    )

    /**
     * Reject the whole configuration if any query parameter is unsafe, unknown or
     * duplicated. Duplicates matter: `sslmode=verify-full&sslmode=require` would
     * otherwise be judged on whichever occurrence the parser happened to read.
     */
    internal fun validateQuery(raw: String): Pair<String, String>? {
        val query = raw.substringAfter('?', "")
        if (query.isBlank()) return null

        val seen = mutableMapOf<String, MutableList<String>>()
        for (pair in query.split('&')) {
            if (pair.isBlank()) continue
            val key = pair.substringBefore('=').trim().lowercase()
            val value = pair.substringAfter('=', "").trim()
            if (key.isEmpty()) {
                return "malformed-url" to "Refusing to provision: $URL_ENV has an empty query parameter name."
            }
            if (key in FORBIDDEN_QUERY_PARAMS) {
                return "forbidden-ssl-parameter" to
                    "Refusing to provision: $URL_ENV must not set the parameter '$key'. " +
                    "SSL factories, hostname verifiers and trust roots are configured by this " +
                    "provisioner (sslmode=verify-full plus $ROOT_CERT_ENV) and may not be " +
                    "overridden through the URL."
            }
            if (key !in ALLOWED_QUERY_PARAMS) {
                return "unknown-query-parameter" to
                    "Refusing to provision: $URL_ENV contains the unsupported parameter '$key'. " +
                    "Only 'sslmode' is accepted; connection settings are configured explicitly."
            }
            seen.getOrPut(key) { mutableListOf() }.add(value)
        }

        val sslModes = seen["sslmode"].orEmpty()
        // Reject the two weaker modes by name here. They still fail the
        // verify-full requirement below, but naming them makes the remedy clear.
        if (sslModes.any { it == "verify-ca" }) {
            return "tls-weak-sslmode" to
                "Refusing to provision: sslmode=verify-ca validates the CA but not the " +
                "hostname. sslmode=verify-full is required."
        }
        if (sslModes.size > 1) {
            return "duplicate-sslmode" to
                "Refusing to provision: $URL_ENV sets sslmode ${sslModes.size} times. " +
                "Exactly one value is required, and it must be verify-full."
        }
        return null
    }

    /**
     * What a root-certificate file turned out to contain.
     *
     * Exactly one certificate is the only acceptable shape. pgJDBC trusts EVERY
     * certificate in `sslrootcert`, so a file whose first certificate matches the
     * expected fingerprint and whose second is another CA would pass a
     * first-certificate-only digest check while letting that CA issue the
     * certificate for the pooler hostname. Counting the certificates is therefore
     * part of pinning, not a cosmetic check.
     */
    internal sealed class RootCertificate {
        /**
         * [sha256] is the digest of the certificate's DER encoding; [pem] is that
         * same encoding re-encoded as PEM, so the bytes that were hashed are the
         * bytes that can be handed to the driver.
         */
        data class Pinned(val sha256: String, val pem: String) : RootCertificate()
        object MoreThanOne : RootCertificate()
        object Unparseable : RootCertificate()
    }

    /** Parse every certificate in [bytes] and classify what it holds. */
    internal fun readRootCertificate(bytes: ByteArray): RootCertificate = try {
        val certificates = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificates(java.io.ByteArrayInputStream(bytes))
        when {
            certificates.isEmpty() -> RootCertificate.Unparseable
            certificates.size > 1 -> RootCertificate.MoreThanOne
            else -> {
                val certificate = certificates.first()
                RootCertificate.Pinned(sha256Of(certificate), pemOf(certificate))
            }
        }
    } catch (error: java.security.cert.CertificateException) {
        RootCertificate.Unparseable
    } catch (error: java.io.IOException) {
        RootCertificate.Unparseable
    }

    /** Convenience for callers that only have a file (tests, tooling). */
    internal fun readRootCertificate(file: java.io.File): RootCertificate = try {
        readRootCertificate(java.nio.file.Files.readAllBytes(file.toPath()))
    } catch (error: java.io.IOException) {
        RootCertificate.Unparseable
    } catch (error: SecurityException) {
        RootCertificate.Unparseable
    }

    /**
     * SHA-256 of the DER encoding of the single certificate in a PEM file, or null
     * when the file is not exactly one parseable X.509 certificate.
     *
     * The JDK's CertificateFactory parses the PEM, so the digest covers the
     * certificate itself rather than the file's whitespace or line endings.
     */
    internal fun sha256OfCertificateFile(file: java.io.File): String? =
        (readRootCertificate(file) as? RootCertificate.Pinned)?.sha256

    private fun sha256Of(certificate: java.security.cert.Certificate): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }

    /**
     * PEM encoding of the certificate's DER bytes.
     *
     * Re-encoding from `encoded` rather than copying the input text means the
     * driver's file is provably the same certificate object whose digest was
     * compared: whitespace, extra blocks or commentary in the source cannot ride
     * along into the trust material.
     */
    private fun pemOf(certificate: java.security.cert.Certificate): String {
        val base64 = java.util.Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
            .encodeToString(certificate.encoded)
        return "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----\n"
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