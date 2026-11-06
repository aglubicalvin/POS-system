package com.businessstore.pos

import com.businessstore.pos.tools.MigrationConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Configuration protection for server/tools/ApplyMigration.java.
 *
 * ApplyMigration writes schema and migration history, so every rejection here must
 * happen BEFORE a connection exists. These tests exercise the real validator with
 * nothing but an environment map: no database, no network.
 */
class MigrationConfigTest {

    private val migrationFile: String = java.io.File.createTempFile("0001_test", ".sql").apply {
        deleteOnExit()
        writeText("select 1;")
    }.absolutePath

    private val valid = mapOf(
        MigrationConfig.CONFIRM_ENV to "yes",
        MigrationConfig.URL_ENV to "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres?sslmode=verify-full",
        MigrationConfig.USER_ENV to "postgres.abcdefghij0123456789",
        MigrationConfig.PASS_ENV to "not-a-real-password",
        MigrationConfig.EXPECTED_ENV to "abcdefghij0123456789",
        MigrationConfig.FILE_ENV to migrationFile,
    )

    private fun rejected(env: Map<String, String>): MigrationConfig.Rejected {
        try {
            MigrationConfig.resolve(env)
        } catch (r: MigrationConfig.Rejected) {
            return r
        }
        throw AssertionError("expected rejection for keys ${env.keys}")
    }

    // ---- the defect this replaces -----------------------------------------

    @Test
    fun `an unset database url is rejected rather than defaulting to Supabase`() {
        // The previous implementation fell back to a hard-coded Supabase pooler
        // URL here, so a typo could apply migrations to a shared project.
        assertEquals("missing-url", rejected(valid - MigrationConfig.URL_ENV).code)
        assertEquals("missing-url", rejected(valid + (MigrationConfig.URL_ENV to "   ")).code)
    }

    // ---- confirmation ------------------------------------------------------

    @Test
    fun `missing confirmation is rejected`() {
        assertEquals("missing-confirmation", rejected(valid - MigrationConfig.CONFIRM_ENV).code)
    }

    @Test
    fun `any confirmation other than exactly yes is rejected`() {
        for (bad in listOf("", "YES", "Yes", "y", "true", "1", "yes ")) {
            assertEquals(
                "missing-confirmation",
                rejected(valid + (MigrationConfig.CONFIRM_ENV to bad)).code,
                "value '$bad' must not be accepted",
            )
        }
    }

    // ---- expected target ---------------------------------------------------

    @Test
    fun `missing expected target is rejected`() {
        assertEquals("missing-expected-target", rejected(valid - MigrationConfig.EXPECTED_ENV).code)
    }

    @Test
    fun `a malformed expected target is rejected`() {
        assertEquals(
            "malformed-expected-target",
            rejected(valid + (MigrationConfig.EXPECTED_ENV to "short")).code,
        )
    }

    @Test
    fun `a target mismatch between username and expected reference is rejected`() {
        assertEquals(
            "target-mismatch",
            rejected(valid + (MigrationConfig.EXPECTED_ENV to "zyxwvutsrq9876543210")).code,
        )
    }

    @Test
    fun `a non-pooler username cannot prove its target`() {
        assertEquals(
            "malformed-user",
            rejected(valid + (MigrationConfig.USER_ENV to "postgres")).code,
        )
    }

    // ---- url shape ---------------------------------------------------------

    @Test
    fun `malformed urls are rejected`() {
        for (bad in listOf(
            "postgresql://db.example.test/postgres",
            "jdbc:postgresql:///postgres",
            "jdbc:postgresql://localhost/postgres",
            "jdbc:mysql://db.example.test/postgres",
        )) {
            assertEquals("malformed-url", rejected(valid + (MigrationConfig.URL_ENV to bad)).code, "url '$bad'")
        }
    }

    @Test
    fun `credentials embedded in the url are rejected`() {
        assertEquals(
            "credentials-in-url",
            rejected(valid + (MigrationConfig.URL_ENV to "jdbc:postgresql://u:p@db.example.test/postgres")).code,
        )
    }

    @Test
    fun `a multi-host url is rejected`() {
        assertEquals(
            "multiple-hosts",
            rejected(valid + (MigrationConfig.URL_ENV to "jdbc:postgresql://a.example.test,b.example.test/postgres")).code,
        )
    }

    @Test
    fun `retargeting query parameters are rejected`() {
        for (param in listOf("host=evil.test", "port=6543", "database=other", "sslfactory=x.Y")) {
            val code = rejected(valid + (MigrationConfig.URL_ENV to
                "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres?$param")).code
            assertTrue(
                code == "forbidden-query-parameter" || code == "unknown-query-parameter",
                "parameter '$param' produced $code",
            )
        }
    }

    @Test
    fun `duplicate sslmode is rejected`() {
        assertEquals(
            "duplicate-sslmode",
            rejected(valid + (MigrationConfig.URL_ENV to
                "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres?sslmode=verify-full&sslmode=require")).code,
        )
    }

    // ---- credentials and file ---------------------------------------------

    @Test
    fun `missing username is rejected`() {
        assertEquals("missing-user", rejected(valid - MigrationConfig.USER_ENV).code)
    }

    @Test
    fun `missing password is rejected unless explicitly allowed`() {
        assertEquals("missing-password", rejected(valid - MigrationConfig.PASS_ENV).code)
        assertEquals("missing-password", rejected(valid + (MigrationConfig.PASS_ENV to "")).code)
        val ok = MigrationConfig.resolve(
            valid + (MigrationConfig.PASS_ENV to "") + (MigrationConfig.PASS_ALLOW_EMPTY_ENV to "yes"),
        )
        assertTrue(ok.password.isEmpty())
    }

    @Test
    fun `a missing migration file is rejected before connecting`() {
        assertEquals("missing-migration-file", rejected(valid - MigrationConfig.FILE_ENV).code)
        assertEquals(
            "missing-migration-file",
            rejected(valid + (MigrationConfig.FILE_ENV to "/nonexistent/0001.sql")).code,
        )
    }

    // ---- acceptance and redaction -----------------------------------------

    @Test
    fun `a fully specified configuration is accepted`() {
        val config = MigrationConfig.resolve(valid)
        assertEquals("abcdefghij0123456789", config.expectedTarget)
        assertFalse(config.describe().contains("not-a-real-password"), "describe() must not print the password")
        assertFalse(config.describe().contains("pooler.supabase.com"), "describe() must not print the host")
    }

    @Test
    fun `rejection messages never echo the password or the host`() {
        val messages = listOf(
            rejected(valid - MigrationConfig.URL_ENV).message!!,
            rejected(valid - MigrationConfig.CONFIRM_ENV).message!!,
            rejected(valid + (MigrationConfig.EXPECTED_ENV to "zyxwvutsrq9876543210")).message!!,
            rejected(valid + (MigrationConfig.URL_ENV to "jdbc:postgresql://u:p@db.example.test/postgres")).message!!,
        )
        for (m in messages) {
            assertFalse(m.contains("not-a-real-password"), "message leaked the password: $m")
            assertFalse(m.contains("pooler.supabase.com"), "message leaked the host: $m")
        }
    }
    // ---- TLS: only verify-full may reach a migration connection -----------

    @Test
    fun `a url without sslmode is rejected`() {
        // No query string at all means no sslmode, so the driver default applies,
        // which is not verify-full. This must be rejected, not silently accepted.
        assertEquals(
            "tls-required",
            rejected(valid + ("CHECK_URL" to
                "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres")).code,
        )
    }

    @Test
    fun `sslmode=disable is rejected`() {
        assertEquals(
            "tls-weak-sslmode",
            rejected(valid + ("CHECK_URL" to
                "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres?sslmode=disable")).code,
        )
    }

    @Test
    fun `sslmode=require is rejected because it verifies no certificate`() {
        assertEquals(
            "tls-weak-sslmode",
            rejected(valid + ("CHECK_URL" to
                "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres?sslmode=require")).code,
        )
    }

    @Test
    fun `sslmode=verify-ca is rejected because it does not verify the hostname`() {
        assertEquals(
            "tls-weak-sslmode",
            rejected(valid + ("CHECK_URL" to
                "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres?sslmode=verify-ca")).code,
        )
    }

    @Test
    fun `sslmode=verify-full is accepted`() {
        // resolve() returns a Config on success and throws Rejected otherwise.
        val config = MigrationConfig.resolve(
            valid + ("CHECK_URL" to
                "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres?sslmode=verify-full"),
        )
        assertNotNull(config, "a verify-full configuration must be accepted")
    }
    // ---- parameter keys are case-sensitive, like pgJDBC -------------------

    @Test
    fun `an uppercase SSLMODE key is rejected`() {
        // pgJDBC matches query keys case-sensitively, so "SSLMODE=verify-full" is
        // ignored by the driver and the connection falls back to its default --
        // opportunistic TLS with no certificate or hostname verification. The
        // validator must not normalise this into an accepted key.
        assertEquals(
            "case-variant-parameter",
            rejected(valid + ("CHECK_URL" to
                "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres?SSLMODE=verify-full")).code,
        )
    }

    @Test
    fun `every mixed-case spelling of sslmode is rejected`() {
        for (key in listOf("SslMode", "sslMode", "Sslmode", "SSLmode", "ssLmode")) {
            assertEquals(
                "case-variant-parameter",
                rejected(valid + ("CHECK_URL" to
                    "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres?$key=verify-full")).code,
                "key '$key' must not be accepted",
            )
        }
    }

    @Test
    fun `a case-variant key is rejected even with a weak value`() {
        // The rejection must not depend on the value: the key itself is unusable.
        assertEquals(
            "case-variant-parameter",
            rejected(valid + ("CHECK_URL" to
                "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres?SSLMODE=disable")).code,
        )
    }

    @Test
    fun `the exact lowercase key is still accepted`() {
        val config = MigrationConfig.resolve(
            valid + ("CHECK_URL" to
                "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres?sslmode=verify-full"),
        )
        assertNotNull(config, "the exact lowercase key must remain accepted")
    }
    // ---- pooler host validation (P2 #2) -----------------------------------

    @Test
    fun `the expected pooler host is accepted`() {
        val config = MigrationConfig.resolve(
            valid + ("CHECK_URL" to
                "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres?sslmode=verify-full"),
        )
        assertNotNull(config, "a Supabase pooler host must be accepted")
    }

    @Test
    fun `an unexpected host is rejected`() {
        // sslmode=verify-full only proves the certificate matches THIS hostname;
        // it does not prove the host belongs to Supabase.
        assertEquals(
            "untrusted-endpoint",
            rejected(valid + ("CHECK_URL" to
                "jdbc:postgresql://db.example.test:5432/postgres?sslmode=verify-full")).code,
        )
    }

    @Test
    fun `a pooler-shaped username on the wrong host is rejected`() {
        // The username is a valid postgres.<ref> and matches POS_EXPECTED_TARGET,
        // but the host is not a Supabase pooler endpoint.
        assertEquals(
            "untrusted-endpoint",
            rejected(valid + ("CHECK_URL" to
                "jdbc:postgresql://attacker.example.test:5432/postgres?sslmode=verify-full")).code,
        )
    }

    @Test
    fun `a lookalike pooler host is rejected`() {
        assertEquals(
            "untrusted-endpoint",
            rejected(valid + ("CHECK_URL" to
                "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com.evil.test:5432/postgres?sslmode=verify-full")).code,
        )
    }

    @Test
    fun `an ip literal host is rejected`() {
        assertEquals(
            "untrusted-endpoint",
            rejected(valid + ("CHECK_URL" to
                "jdbc:postgresql://203.0.113.10:5432/postgres?sslmode=verify-full")).code,
        )
    }

    @Test
    fun `a non-pooler port is rejected`() {
        assertEquals(
            "untrusted-endpoint",
            rejected(valid + ("CHECK_URL" to
                "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5433/postgres?sslmode=verify-full")).code,
        )
    }

    @Test
    fun `the transaction pooler port is accepted`() {
        val config = MigrationConfig.resolve(
            valid + ("CHECK_URL" to
                "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:6543/postgres?sslmode=verify-full"),
        )
        assertNotNull(config, "port 6543 is a valid pooler port")
    }

    // ---- project reference still enforced ---------------------------------

    @Test
    fun `a mismatched project reference is rejected`() {
        assertEquals(
            "target-mismatch",
            rejected(valid + ("MIGRATION_EXPECTED_TARGET" to "zyxwvutsrq9876543210")).code,
        )
    }

    @Test
    fun `a missing expected target is rejected`() {
        assertEquals(
            "missing-expected-target",
            rejected(valid - "MIGRATION_EXPECTED_TARGET").code,
        )
    }
}