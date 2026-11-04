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

    private fun generate(): Pair<String, String> {
        val dir = java.nio.file.Files.createTempDirectory("fake-root").toFile()
        dir.deleteOnExit()
        val keytool = resolveKeytool()
        val keystore = java.io.File(dir, "ks.p12")
        val certOut = java.io.File(dir, "root.crt")

        fun run(vararg args: String) {
            val process = ProcessBuilder(listOf(keytool) + args)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            check(process.waitFor() == 0) { "keytool failed: ${args.firstOrNull()}" }
        }

        run(
            "-genkeypair", "-alias", "testroot", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=fake-root", "-validity", "1",
            "-keystore", keystore.absolutePath, "-storepass", "changeit", "-storetype", "PKCS12",
        )
        run(
            "-exportcert", "-rfc", "-alias", "testroot",
            "-keystore", keystore.absolutePath, "-storepass", "changeit",
            "-file", certOut.absolutePath,
        )

        val fingerprint = requireNotNull(ProvisionTarget.sha256OfCertificateFile(certOut)) {
            "the generated certificate could not be fingerprinted"
        }
        certOut.deleteOnExit()
        keystore.deleteOnExit()
        return certOut.absolutePath to fingerprint
    }

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
