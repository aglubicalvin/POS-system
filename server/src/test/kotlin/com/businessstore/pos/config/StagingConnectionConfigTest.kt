package com.businessstore.pos.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

/**
 * Contract tests for [StagingConnectionConfig]: the staged certificate must match
 * an independently supplied SHA-256, and the connection string must be built from
 * validated values only.
 *
 * No database is contacted and no real Supabase material is used: the certificate
 * is a synthetic DER blob wrapped in PEM, which is all the fingerprint rule
 * inspects.
 */
class StagingConnectionConfigTest {

    private val projectRef = "stagingref0000000001"
    private val poolerHost = "aws-0-eu-west-2.pooler.supabase.com"

    /** Write a synthetic PEM certificate and return (path, correct fingerprint). */
    private fun pem(dir: Path, der: ByteArray = ByteArray(48) { it.toByte() }): Pair<Path, String> {
        val path = dir.resolve("staging-root-ca.pem")
        val body = Base64.getEncoder().encodeToString(der)
        Files.writeString(
            path,
            "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n",
        )
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(der)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return path to fingerprint
    }

    private fun env(certPath: Path, fingerprint: String) = mapOf(
        StagingConnectionConfig.PROJECT_REF_ENV to projectRef,
        StagingConnectionConfig.ADMIN_USER_ENV to "postgres.$projectRef",
        StagingConnectionConfig.POOLER_HOST_ENV to poolerHost,
        StagingConnectionConfig.ROOT_CERT_ENV to certPath.toString(),
        StagingConnectionConfig.ROOT_CERT_SHA256_ENV to fingerprint,
    )

    @Test
    fun `accepts a staged certificate whose fingerprint matches its DER encoding`(@TempDir dir: Path) {
        val (cert, fingerprint) = pem(dir)
        val outcome = StagingConnectionConfig.resolve(env(cert, fingerprint))
        assertTrue(outcome is StagingConnectionConfig.Outcome.Ok, "expected acceptance: $outcome")
        val config = (outcome as StagingConnectionConfig.Outcome.Ok).config
        assertEquals(projectRef, config.projectRef)
        assertEquals("postgres.$projectRef", config.adminUser)
        assertEquals("verify-full", config.sslMode)
        assertEquals(cert.toAbsolutePath().normalize(), config.certPath)
    }

    @Test
    fun `accepts a colon-separated uppercase fingerprint`(@TempDir dir: Path) {
        val (cert, fingerprint) = pem(dir)
        val spaced = fingerprint.uppercase().chunked(2).joinToString(":")
        val outcome = StagingConnectionConfig.resolve(env(cert, spaced))
        assertTrue(outcome is StagingConnectionConfig.Outcome.Ok, "expected acceptance: $outcome")
    }

    @Test
    fun `rejects a certificate that does not match the supplied fingerprint`(@TempDir dir: Path) {
        val (cert, _) = pem(dir)
        val wrong = "a".repeat(64)
        val outcome = StagingConnectionConfig.resolve(env(cert, wrong))
        assertTrue(outcome is StagingConnectionConfig.Outcome.Rejected)
        assertEquals(
            "root-certificate-fingerprint-mismatch",
            (outcome as StagingConnectionConfig.Outcome.Rejected).code,
        )
        assertFalse(outcome.message.contains(wrong), "the refusal must not echo the fingerprint")
    }

    @Test
    fun `rejects a rotated certificate with the old fingerprint`(@TempDir dir: Path) {
        val (cert, oldFingerprint) = pem(dir)
        // Same path, different content: the fingerprint must be re-checked against
        // the bytes on disk, not trusted from the earlier read.
        pem(dir, ByteArray(48) { (it + 1).toByte() })
        val outcome = StagingConnectionConfig.resolve(env(cert, oldFingerprint))
        assertTrue(outcome is StagingConnectionConfig.Outcome.Rejected, "expected rejection: $outcome")
    }

    @Test
    fun `rejects a missing certificate file`(@TempDir dir: Path) {
        val missing = dir.resolve("absent.pem")
        val outcome = StagingConnectionConfig.resolve(env(missing, "b".repeat(64)))
        assertTrue(outcome is StagingConnectionConfig.Outcome.Rejected)
        assertEquals(
            "missing-staging-root-certificate",
            (outcome as StagingConnectionConfig.Outcome.Rejected).code,
        )
    }

    @Test
    fun `rejects a malformed fingerprint`(@TempDir dir: Path) {
        val (cert, _) = pem(dir)
        for (bad in listOf("", "   ", "not-a-fingerprint", "f".repeat(63), "f".repeat(65), "g".repeat(64))) {
            val outcome = StagingConnectionConfig.resolve(env(cert, bad))
            assertTrue(outcome is StagingConnectionConfig.Outcome.Rejected, "fingerprint <<$bad>> must be refused")
        }
    }

    @Test
    fun `rejects a missing fingerprint`(@TempDir dir: Path) {
        val (cert, _) = pem(dir)
        val outcome = StagingConnectionConfig.resolve(
            env(cert, "").minus(StagingConnectionConfig.ROOT_CERT_SHA256_ENV),
        )
        assertTrue(outcome is StagingConnectionConfig.Outcome.Rejected)
        assertEquals(
            "missing-staging-root-certificate-fingerprint",
            (outcome as StagingConnectionConfig.Outcome.Rejected).code,
        )
    }

    @Test
    fun `rejects a certificate that is not decodable PEM`(@TempDir dir: Path) {
        val broken = dir.resolve("broken.pem")
        Files.writeString(broken, "-----BEGIN CERTIFICATE-----\n!!!not base64!!!\n-----END CERTIFICATE-----\n")
        val outcome = StagingConnectionConfig.resolve(env(broken, "c".repeat(64)))
        assertTrue(outcome is StagingConnectionConfig.Outcome.Rejected, "expected rejection: $outcome")
    }

    @Test
    fun `builds a verify-full connection string from validated values only`(@TempDir dir: Path) {
        val (cert, fingerprint) = pem(dir)
        val config = StagingConnectionConfig.require(env(cert, fingerprint))
        val connectionString = config.connectionString()
        assertTrue(connectionString.contains("host=$poolerHost port=5432 dbname=postgres"), connectionString)
        assertTrue(connectionString.contains("sslmode=verify-full"), connectionString)
        assertTrue(connectionString.contains("sslrootcert=${config.certPath}"), connectionString)
        assertFalse(connectionString.contains("password"), connectionString)
        assertFalse(connectionString.contains(fingerprint), connectionString)
    }

    @Test
    fun `no environment variable can weaken the TLS mode`(@TempDir dir: Path) {
        val (cert, fingerprint) = pem(dir)
        val weakened = env(cert, fingerprint) + mapOf(
            "POS_STAGING_SSL_MODE" to "require",
            "PGSSLMODE" to "disable",
            "sslmode" to "prefer",
        )
        val config = StagingConnectionConfig.require(weakened)
        assertEquals("verify-full", config.sslMode)
        assertTrue(config.connectionString().contains("sslmode=verify-full"))
    }

    @Test
    fun `a mismatched administrator login is rejected before any file is read`(@TempDir dir: Path) {
        val (cert, fingerprint) = pem(dir)
        val outcome = StagingConnectionConfig.resolve(
            env(cert, fingerprint) + mapOf(
                StagingConnectionConfig.ADMIN_USER_ENV to "postgres.otherref00000000000z",
            ),
        )
        assertTrue(outcome is StagingConnectionConfig.Outcome.Rejected)
        assertEquals("ref-mismatch", (outcome as StagingConnectionConfig.Outcome.Rejected).code)
    }
}
