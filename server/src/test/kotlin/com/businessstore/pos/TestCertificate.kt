package com.businessstore.pos

import com.businessstore.pos.tools.ProvisionTarget

/**
 * A real, throwaway self-signed X.509 root certificate and its SHA-256 digest.
 *
 * The provisioner pins its trust root by comparing the certificate's SHA-256
 * fingerprint against an independently supplied expectation, so a placeholder
 * PEM cannot exercise that check honestly: it would fail parsing rather than
 * prove the digest comparison works. Generated once per test JVM.
 *
 * The JDK's public API cannot *create* a certificate, so keytool from the
 * running JDK (which ships with every JDK) is used. A missing keytool fails the
 * test loudly instead of silently degrading the check.
 */
object TestCertificate {

    /** (PEM path, lowercase hex SHA-256 of the certificate's DER encoding). */
    private val generated: Pair<String, String> by lazy { generate() }

    /** Absolute path to the PEM-encoded certificate. */
    val path: String get() = generated.first

    /** Lowercase hex SHA-256 of the certificate's DER encoding. */
    val sha256: String get() = generated.second

    /**
     * A PEM file holding the pinned root FOLLOWED BY a second, unrelated CA.
     *
     * pgJDBC trusts every certificate in `sslrootcert`, so this is the shape that
     * a first-certificate-only fingerprint check would wave through: the digest of
     * the first block still matches while the second CA can issue the pooler's
     * certificate.
     */
    val pathWithExtraCa: String by lazy {
        val combined = java.io.File(tempDir("fake-root-multi"), "two-certs.crt")
        combined.deleteOnExit()
        val extra = createCertificate("extraca", "CN=extra-ca", tempDir("fake-extra-ca"))
        extra.deleteOnExit()
        combined.writeText(
            java.io.File(generated.first).readText() + "\n" + extra.readText(),
        )
        combined.absolutePath
    }

    /**
     * A SECOND, unrelated self-signed CA.
     *
     * This is the certificate an attacker would substitute for the pinned root
     * after configuration resolution, so the TOCTOU tests can prove that a swap of
     * the caller-controlled file cannot change what the driver trusts.
     */
    private val alternate: Pair<String, String> by lazy {
        val certOut = createCertificate("otherroot", "CN=other-root", tempDir("other-root"))
        val fingerprint = requireNotNull(ProvisionTarget.sha256OfCertificateFile(certOut)) {
            "the alternate certificate could not be fingerprinted"
        }
        certOut.deleteOnExit()
        certOut.absolutePath to fingerprint
    }

    /** Absolute path to a second, unrelated CA certificate. */
    val otherPath: String get() = alternate.first

    /** Lowercase hex SHA-256 of the alternate certificate's DER encoding. */
    val otherSha256: String get() = alternate.second

    /** PEM text of the alternate certificate. */
    val otherPem: String get() = java.io.File(alternate.first).readText()

    /**
     * A writable copy of [sourcePath], so a test can replace its contents after the
     * configuration has been resolved -- the exact window the pinning bug exploited.
     */
    fun mutableCopy(sourcePath: String, name: String): java.io.File {
        val copy = java.io.File(tempDir("mutable-cert"), name)
        copy.deleteOnExit()
        copy.writeText(java.io.File(sourcePath).readText())
        return copy
    }

    private fun generate(): Pair<String, String> {
        val certOut = createCertificate("testroot", "CN=fake-root", tempDir("fake-root"))
        val fingerprint = requireNotNull(ProvisionTarget.sha256OfCertificateFile(certOut)) {
            "the generated certificate could not be fingerprinted"
        }
        certOut.deleteOnExit()
        return certOut.absolutePath to fingerprint
    }

    /** Generate one self-signed certificate and export it as PEM. */
    private fun createCertificate(
        alias: String,
        distinguishedName: String,
        dir: java.io.File,
    ): java.io.File {
        val keytool = resolveKeytool()
        val keystore = java.io.File(dir, "$alias.p12")
        val certOut = java.io.File(dir, "$alias.crt")
        keystore.deleteOnExit()

        fun run(vararg args: String) {
            val process = ProcessBuilder(listOf(keytool) + args)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            check(process.waitFor() == 0) { "keytool failed: ${args.firstOrNull()}" }
        }

        run(
            "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
            "-dname", distinguishedName, "-validity", "1",
            "-keystore", keystore.absolutePath, "-storepass", "changeit", "-storetype", "PKCS12",
        )
        run(
            "-exportcert", "-rfc", "-alias", alias,
            "-keystore", keystore.absolutePath, "-storepass", "changeit",
            "-file", certOut.absolutePath,
        )
        return certOut
    }

    private fun tempDir(prefix: String): java.io.File =
        java.nio.file.Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    /** keytool next to the running JDK, falling back to PATH. */
    private fun resolveKeytool(): String {
        val javaHome = System.getProperty("java.home")
        for (name in listOf("bin/keytool", "bin/keytool.exe")) {
            val candidate = java.io.File(javaHome, name)
            if (candidate.canExecute()) return candidate.absolutePath
        }
        return "keytool"
    }
}
