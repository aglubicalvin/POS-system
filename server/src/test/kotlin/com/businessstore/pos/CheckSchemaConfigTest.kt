package com.businessstore.pos

import com.businessstore.pos.tools.CheckSchemaConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Configuration protection for server/tools/CheckSchema.java.
 *
 * CheckSchemaConfig is the gate the tool runs BEFORE creating any connection, so
 * these tests exercise the real implementation with nothing but an environment
 * map: no database, no network, no Supabase.
 */
class CheckSchemaConfigTest {

    private val valid = mapOf(
        "CHECK_URL" to "jdbc:postgresql://db.example.test:5432/postgres",
        "CHECK_USER" to "checker",
        "CHECK_PASS" to "not-a-real-password",
    )

    private fun rejected(env: Map<String, String>): CheckSchemaConfig.Rejected {
        try {
            CheckSchemaConfig.resolve(env)
        } catch (rejected: CheckSchemaConfig.Rejected) {
            return rejected
        }
        throw AssertionError("expected rejection for $env")
    }

    @Test
    fun `missing url is rejected`() {
        assertEquals("missing-url", rejected(valid - "CHECK_URL").code)
    }

    @Test
    fun `blank url is rejected`() {
        assertEquals("missing-url", rejected(valid + ("CHECK_URL" to "   ")).code)
    }

    @Test
    fun `malformed urls are rejected`() {
        for (bad in listOf(
            "postgresql://db.example.test/postgres",
            "jdbc:postgresql:///postgres",
            "jdbc:postgresql://localhost/postgres",
            "jdbc:mysql://db.example.test/postgres",
        )) {
            assertEquals("malformed-url", rejected(valid + ("CHECK_URL" to bad)).code, "url '$bad'")
        }
    }

    @Test
    fun `credentials embedded in the url are rejected`() {
        assertEquals(
            "credentials-in-url",
            rejected(valid + ("CHECK_URL" to "jdbc:postgresql://u:p@db.example.test/postgres")).code,
        )
    }

    @Test
    fun `credentials in url query parameters are rejected`() {
        // pgJDBC accepts password/user as URL parameters too, so rejecting only
        // userinfo would leave the CHECK_USER / CHECK_PASS contract bypassable and
        // put a secret into a value the driver can echo in diagnostics.
        for (query in listOf("?password=secret", "?Password=secret", "?user=postgres", "?sslpassword=x", "?sslmode=require&password=secret")) {
            assertEquals(
                "credentials-in-url",
                rejected(valid + ("CHECK_URL" to "jdbc:postgresql://db.example.test/postgres$query")).code,
                query,
            )
        }
        // A non-credential query parameter is still allowed (resolves, no throw).
        CheckSchemaConfig.resolve(
            valid + ("CHECK_URL" to "jdbc:postgresql://db.example.test/postgres?sslmode=require"),
        )
    }

    @Test
    fun `connection target overrides in url query parameters are rejected`() {
        // CheckSchema hands the raw URL to pgJDBC, which lets a query parameter
        // replace the authority. Validating the host and then forwarding ?host=...
        // would send CHECK_USER / CHECK_PASS to an unchecked host.
        for (query in listOf(
            "?host=attacker.example.test",
            "?HOST=attacker.example.test",
            "?port=6543",
            "?database=other",
            "?dbname=other",
            "?socketFactory=com.example.SocketFactory",
            "?sslfactory=org.postgresql.ssl.NonValidatingFactory",
            "?sslhostnameverifier=com.example.AnyVerifier",
            "?sslmode=require&host=attacker.example.test",
        )) {
            assertEquals(
                "unsupported-query-parameter",
                rejected(valid + ("CHECK_URL" to "jdbc:postgresql://db.example.test/postgres$query")).code,
                query,
            )
        }
    }

    @Test
    fun `a rejected query parameter is neither echoed nor allowed to carry a value`() {
        val message = rejected(
            valid + ("CHECK_URL" to "jdbc:postgresql://db.example.test/postgres?host=attacker.example.test"),
        ).message!!
        assertFalse(message.contains("attacker.example.test"), "message leaked the value: $message")
        assertFalse(message.contains("db.example.test"), "message leaked the host: $message")
    }

    @Test
    fun `missing username is rejected`() {
        assertEquals("missing-user", rejected(valid - "CHECK_USER").code)
        assertEquals("missing-user", rejected(valid + ("CHECK_USER" to "  ")).code)
    }

    @Test
    fun `missing password is rejected`() {
        assertEquals("missing-password", rejected(valid - "CHECK_PASS").code)
    }

    @Test
    fun `an empty password requires an explicit opt-in`() {
        // A local trust/peer-auth database legitimately has no password, but that
        // must be a stated decision rather than an accident.
        assertEquals("missing-password", rejected(valid + ("CHECK_PASS" to "")).code)
        val accepted = CheckSchemaConfig.resolve(valid + ("CHECK_PASS" to "") + ("CHECK_PASS_ALLOW_EMPTY" to "yes"))
        assertTrue(accepted.password.isEmpty())
    }

    @Test
    fun `a valid configuration is accepted`() {
        val config = CheckSchemaConfig.resolve(valid)
        assertEquals("checker", config.user)
        assertNull(config.expectedUser)
        assertNull(config.expectedDatabase)
    }

    @Test
    fun `a configured expected identity that does not match the session is rejected`() {
        val config = CheckSchemaConfig.resolve(
            valid + ("CHECK_EXPECTED_USER" to "postgres.abcdefghij0123456789") +
                ("CHECK_EXPECTED_DATABASE" to "postgres"),
        )
        // Matching session passes.
        CheckSchemaConfig.verifyIdentity(config, "postgres.abcdefghij0123456789", "postgres")

        // A mismatched connected role fails closed.
        val userMismatch = runCatching {
            CheckSchemaConfig.verifyIdentity(config, "postgres.zyxwvutsrq9876543210", "postgres")
        }.exceptionOrNull()
        assertEquals("identity-mismatch", (userMismatch as CheckSchemaConfig.Rejected).code)

        // A mismatched connected database fails closed too.
        val dbMismatch = runCatching {
            CheckSchemaConfig.verifyIdentity(config, "postgres.abcdefghij0123456789", "somewhere_else")
        }.exceptionOrNull()
        assertEquals("identity-mismatch", (dbMismatch as CheckSchemaConfig.Rejected).code)
    }

    @Test
    fun `rejection messages never echo the password or the host`() {
        val messages = listOf<String>(
            rejected(valid - "CHECK_URL").message!!,
            rejected(valid + ("CHECK_URL" to "jdbc:postgresql://u:p@db.example.test/postgres")).message!!,
            rejected(valid - "CHECK_USER").message!!,
            rejected(valid - "CHECK_PASS").message!!,
            rejected(valid + ("CHECK_URL" to "nonsense")).message!!,
        )
        for (message in messages) {
            assertFalse(message.contains("not-a-real-password"), "message leaked the password: $message")
            assertFalse(message.contains("db.example.test"), "message leaked the host: $message")
        }
    }
}