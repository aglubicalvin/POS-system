package com.businessstore.pos

import com.businessstore.pos.tools.PinnedTrustMaterial
import com.businessstore.pos.tools.Provision
import com.businessstore.pos.tools.ProvisionTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * Regression tests for the certificate-pinning TOCTOU finding on PR #2.
 *
 * `ProvisionTarget.resolve()` proves the caller's certificate matches an
 * independently supplied SHA-256. If the resolved object merely remembered the
 * PATH, pgJDBC would reopen that file at connection time and a local principal
 * could swap in its own CA after the check -- then intercept the pooler
 * connection and collect `POS_DATABASE_PASS`.
 *
 * These tests replace the source certificate AFTER resolution and prove the
 * connector still trusts only the verified bytes, held in a private copy that the
 * caller never named. No Supabase or production database is contacted.
 */
class CertificatePinningTest {

    private fun env(cert: String, fingerprint: String): Map<String, String> = mapOf(
        ProvisionTarget.CONFIRM_ENV to "yes",
        ProvisionTarget.URL_ENV to
            "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres?sslmode=verify-full",
        ProvisionTarget.USER_ENV to "postgres.abcdefghij0123456789",
        ProvisionTarget.PASS_ENV to "not-a-real-password",
        ProvisionTarget.EXPECTED_ENV to "abcdefghij0123456789",
        ProvisionTarget.ROOT_CERT_ENV to cert,
        ProvisionTarget.ROOT_CERT_SHA256_ENV to fingerprint,
    )

    private fun rejected(env: Map<String, String>): ProvisionTarget.Outcome.Rejected {
        val outcome = ProvisionTarget.resolve(env)
        assertTrue(outcome is ProvisionTarget.Outcome.Rejected, "expected rejection, got $outcome")
        return outcome as ProvisionTarget.Outcome.Rejected
    }

    private fun targetFor(
        cert: String = TestCertificate.path,
        fingerprint: String = TestCertificate.sha256,
    ): ProvisionTarget.Target {
        val outcome = ProvisionTarget.resolve(env(cert, fingerprint))
        assertTrue(outcome is ProvisionTarget.Outcome.Ok, "expected acceptance, got $outcome")
        return (outcome as ProvisionTarget.Outcome.Ok).target
    }

    private fun ready(
        target: ProvisionTarget.Target,
        parent: Path = Paths.get(System.getProperty("java.io.tmpdir")),
    ): PinnedTrustMaterial {
        val outcome = PinnedTrustMaterial.create(target, parent)
        assertTrue(outcome is PinnedTrustMaterial.Outcome.Ready, "expected trust material, got $outcome")
        return (outcome as PinnedTrustMaterial.Outcome.Ready).material
    }

    private fun digestOf(path: String): String? =
        ProvisionTarget.sha256OfCertificateFile(File(path))

    private fun listing(dir: Path): Set<String> = dir.toFile().list()?.toSet() ?: emptySet()

    // ---- A: replacement after verification cannot change the trust root ------

    @Test
    fun `replacing the source certificate after resolution does not change the connector trust root`() {
        // The source is writable by the same account that runs the test, which is
        // exactly the window: verify, then replace, then let pgJDBC read.
        val source = TestCertificate.mutableCopy(TestCertificate.path, "pinned-root.crt")
        val target = targetFor(source.absolutePath)
        assertEquals(TestCertificate.sha256, target.rootCertificateSha256)

        // Swap in an unrelated CA AFTER the fingerprint was verified.
        source.writeText(TestCertificate.otherPem)
        assertEquals(
            TestCertificate.otherSha256,
            digestOf(source.absolutePath),
            "the source swap must really have happened for this test to mean anything",
        )

        ready(target).use { trust ->
            val ds = Provision.buildDataSource(target, trust)
            assertEquals("verify-full", ds.sslMode)
            val trustPath = requireNotNull(ds.sslRootCert) { "the connector must pin a root certificate" }
            assertNotEquals(source.absolutePath, trustPath, "the caller's path must not be reused")
            assertNotEquals(TestCertificate.otherSha256, digestOf(trustPath))
            assertEquals(
                TestCertificate.sha256,
                digestOf(trustPath),
                "pgJDBC must be pointed at the verified certificate, not the replacement",
            )
            assertEquals(TestCertificate.sha256, trust.sha256)
            assertEquals("aws-0-eu-west-2.pooler.supabase.com", ds.serverNames.single())
        }
    }

    // ---- B: replacement/redirect cannot become the trust root ----------------

    @Test
    fun `a replaced or redirected source cannot become the trust root`() {
        val source = TestCertificate.mutableCopy(TestCertificate.path, "redirected-root.crt")
        val target = targetFor(source.absolutePath)

        // Replacement: an independent fingerprint no longer matches the source.
        source.writeText(TestCertificate.otherPem)
        assertEquals(
            "root-certificate-mismatch",
            rejected(env(source.absolutePath, TestCertificate.sha256)).code,
            "a replacement CA must not satisfy the pinned fingerprint",
        )

        val trust = ready(target)
        try {
            // Redirection: the caller's path stops being a readable file entirely.
            source.delete()
            assertTrue(source.mkdir(), "the source path must be repurposed for this test")
            assertEquals("missing-root-certificate", rejected(env(source.absolutePath, TestCertificate.sha256)).code)

            // The material already built from the verified snapshot is untouched by
            // either manipulation, and still holds the pinned certificate.
            assertEquals(TestCertificate.sha256, digestOf(trust.path))
            assertEquals("verify-full", Provision.buildDataSource(target, trust).sslMode)
        } finally {
            trust.close()
        }
    }

    // ---- C: certificate and trust-material integrity -------------------------

    @Test
    fun `only exactly one certificate matching an independent fingerprint is trusted`() {
        // Wrong fingerprint.
        assertEquals(
            "root-certificate-mismatch",
            rejected(env(TestCertificate.path, "0".repeat(64))).code,
        )
        // Two certificates: the driver would trust both, so a first-certificate
        // digest is not enough.
        assertEquals(
            "multiple-root-certificates",
            rejected(env(TestCertificate.pathWithExtraCa, TestCertificate.sha256)).code,
        )
        // Not a certificate at all.
        val bogus = File.createTempFile("not-a-cert", ".crt")
        bogus.deleteOnExit()
        bogus.writeText("this is not a PEM certificate")
        assertEquals(
            "malformed-root-certificate",
            rejected(env(bogus.absolutePath, TestCertificate.sha256)).code,
        )
        // Missing file.
        assertEquals(
            "missing-root-certificate",
            rejected(env("/nonexistent/does-not-exist.crt", TestCertificate.sha256)).code,
        )
    }

    @Test
    fun `secure trust material that cannot be created fails closed`() {
        val target = targetFor()
        // A file where a directory must be: creation cannot succeed, and no
        // fallback to the caller's path is allowed.
        val notADirectory = Files.createTempFile("pin-not-a-dir", ".tmp")
        notADirectory.toFile().deleteOnExit()
        val outcome = PinnedTrustMaterial.create(target, notADirectory)
        assertTrue(outcome is PinnedTrustMaterial.Outcome.Unavailable, "expected failure, got $outcome")
        assertEquals("pinned-trust-unavailable", (outcome as PinnedTrustMaterial.Outcome.Unavailable).code)

        // A snapshot that does not match the pinned digest must never be written:
        // the copy is re-verified after creation and deleted on mismatch.
        val parent = Files.createTempDirectory("pin-integrity")
        parent.toFile().deleteOnExit()
        val tampered = target.copy(rootCertificateSha256 = "0".repeat(64))
        val integrity = PinnedTrustMaterial.create(tampered, parent)
        assertTrue(integrity is PinnedTrustMaterial.Outcome.Unavailable)
        assertEquals("pinned-trust-integrity", (integrity as PinnedTrustMaterial.Outcome.Unavailable).code)
        assertEquals(emptySet<String>(), listing(parent), "a failed copy must leave nothing behind")
    }

    @Test
    fun `the private copy is removed after a successful and a failed operation`() {
        val parent = Files.createTempDirectory("pin-cleanup")
        parent.toFile().deleteOnExit()
        val before = listing(parent)
        val target = targetFor()

        // Success.
        val material = ready(target, parent)
        val dir = File(material.path).parentFile
        assertTrue(dir.isDirectory, "the private directory must exist while provisioning runs")
        material.use { }
        assertFalse(dir.exists(), "the copy must be removed after a successful run")

        // Failure (an unexpected error escaping the provisioning body).
        val failing = ready(target, parent)
        val failingDir = File(failing.path).parentFile
        val thrown = runCatching {
            failing.use { throw IllegalStateException("simulated provisioning failure") }
        }
        assertTrue(thrown.exceptionOrNull() is IllegalStateException)
        assertFalse(failingDir.exists(), "the copy must be removed when provisioning fails too")
        assertEquals(before, listing(parent), "no trust material may accumulate in the temp root")
    }

    @Test
    fun `the private certificate is inaccessible to other local accounts`() {
        ready(targetFor()).use { trust ->
            val file = Paths.get(trust.path)
            if (file.fileSystem.supportedFileAttributeViews().contains("posix")) {
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                assertEquals(
                    "rwx------",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(file.parent)),
                )
            } else {
                // Windows: the inherited ACL is replaced with owner-only grants.
                val acl = requireNotNull(
                    Files.getFileAttributeView(file, AclFileAttributeView::class.java),
                ).acl
                assertTrue(acl.isNotEmpty(), "the copy must carry an explicit ACL")
                assertTrue(acl.all { it.type() == AclEntryType.ALLOW }, "a private file needs no deny entries")
                val names = acl.map { it.principal().name.lowercase() }
                assertFalse(
                    names.any { it.contains("everyone") || it.contains("authenticated users") || it.endsWith("\\users") },
                    "the ACL must not grant other local accounts: $names",
                )
            }
        }
    }
    // ---- cleanup failure semantics ----------------------------------------

    @Test
    fun `a cleanup failure does not mask the provisioning exception`() {
        // `use` closes in a finally. If close() throws while the body is already
        // failing, Kotlin attaches the close failure as SUPPRESSED and keeps the
        // body's exception primary. This pins that behaviour for the material.
        val body = IllegalStateException("provisioning failed")
        val thrown = runCatching {
            FakeMaterial().use { throw body }
        }.exceptionOrNull()

        assertSame(body, thrown, "the provisioning exception must remain primary")
        assertEquals(
            listOf("cleanup failed"),
            thrown!!.suppressed.map { it.message },
            "the cleanup failure must be attached as suppressed, not swallowed",
        )
    }

    @Test
    fun `a cleanup failure on a successful run is still reported`() {
        // If the body succeeded, a close() failure must surface rather than be
        // silently discarded: leftover private material is a real condition.
        val thrown = runCatching { FakeMaterial().use { } }.exceptionOrNull()
        assertNotNull(thrown, "a cleanup failure must not be swallowed on success")
        assertEquals("cleanup failed", thrown!!.message)
    }

    /** Minimal AutoCloseable that always fails to clean up. */
    private class FakeMaterial : AutoCloseable {
        override fun close() {
            throw IllegalStateException("cleanup failed")
        }
    }
    @Test
    fun `a real cleanup failure is reported and does not mask the primary error`() {
        // Force a genuine deletion failure: replace the private certificate file
        // with a NON-EMPTY DIRECTORY of the same name, so Files.deleteIfExists(file)
        // fails with DirectoryNotEmptyException.
        val parent = Files.createTempDirectory("pin-cleanup")
        val material = ready(targetFor(), parent)
        val filePath = Paths.get(material.path)

        Files.delete(filePath)
        Files.createDirectory(filePath)
        Files.writeString(filePath.resolve("blocker"), "x")

        val primary = IllegalStateException("provisioning failed")
        val thrown = runCatching { material.use { throw primary } }.exceptionOrNull()

        assertSame(primary, thrown, "the provisioning exception must remain primary")
        assertTrue(
            thrown!!.suppressed.any { it.message?.contains("pinned trust cleanup failed") == true },
            "the cleanup failure must be reported as suppressed, got ${thrown.suppressed.map { it.message }}",
        )
        // The message must not leak certificate material.
        assertFalse(
            thrown.suppressed.any { it.message?.contains("BEGIN CERTIFICATE") == true },
            "cleanup messages must not contain certificate contents",
        )

        // Tidy up: the material's own directory still exists under `parent`
        // (its deletion failed by design), so remove the tree.
        Files.deleteIfExists(filePath.resolve("blocker"))
        Files.deleteIfExists(filePath)
        deleteTree(parent)
    }

    @Test
    fun `a successful run with a failing cleanup surfaces the cleanup error`() {
        val parent = Files.createTempDirectory("pin-cleanup-ok")
        val material = ready(targetFor(), parent)
        val filePath = Paths.get(material.path)
        Files.delete(filePath)
        Files.createDirectory(filePath)
        Files.writeString(filePath.resolve("blocker"), "x")

        val thrown = runCatching { material.use { } }.exceptionOrNull()
        assertNotNull(thrown, "a cleanup failure must not be swallowed on success")
        assertTrue(thrown!!.message?.contains("pinned trust cleanup failed") == true)

        Files.deleteIfExists(filePath.resolve("blocker"))
        Files.deleteIfExists(filePath)
        deleteTree(parent)
    }

    /** Best-effort recursive delete for test teardown only. */
    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
