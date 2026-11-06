package com.businessstore.pos

import com.businessstore.pos.tools.MigrationConfig
import org.junit.jupiter.api.Assertions.assertEquals
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
        MigrationConfig.URL_ENV to "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres",
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
}