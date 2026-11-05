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
        // A non-loopback target with verified TLS: the only remote shape accepted.
        "CHECK_URL" to "jdbc:postgresql://db.example.test:5432/postgres?sslmode=verify-full",
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
            valid + ("CHECK_URL" to "jdbc:postgresql://db.example.test/postgres?sslmode=verify-full"),
        )
    }

    @Test
    fun `a remote target must verify TLS`() {
        // CHECK_PASS travels to this target. Without verify-full the password is
        // either sent unencrypted or sent to an unverified peer, so every weaker
        // mode is refused before a connection exists.
        for (query in listOf(
            "",
            "?sslmode=disable",
            "?sslmode=allow",
            "?sslmode=prefer",
            "?sslmode=require",
            "?sslmode=verify-ca",
        )) {
            assertEquals(
                "tls-required",
                rejected(valid + ("CHECK_URL" to "jdbc:postgresql://db.example.test:5432/postgres$query")).code,
                "query '$query'",
            )
        }

        // A duplicate would let the first occurrence satisfy a check while the
        // driver applied the last.
        assertEquals(
            "duplicate-sslmode",
            rejected(
                valid + ("CHECK_URL" to
                    "jdbc:postgresql://db.example.test:5432/postgres?sslmode=verify-full&sslmode=disable"),
            ).code,
        )

        // An unrecognised mode is a typo, not a policy change.
        assertEquals(
            "malformed-url",
            rejected(valid + ("CHECK_URL" to "jdbc:postgresql://db.example.test/postgres?sslmode=verify-maybe")).code,
        )

        // Remote with verified TLS is the accepted shape.
        CheckSchemaConfig.resolve(
            valid + ("CHECK_URL" to "jdbc:postgresql://db.example.test:5432/postgres?sslmode=verify-full"),
        )

        // A loopback target has no network to intercept, so local development keeps
        // the weaker modes.
        for (query in listOf("", "?sslmode=disable", "?sslmode=require")) {
            CheckSchemaConfig.resolve(
                valid + ("CHECK_URL" to "jdbc:postgresql://127.0.0.1:5432/postgres$query"),
            )
        }
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
    fun `multi host authorities are rejected`() {
        // pgJDBC reads a comma as a failover list and tries each endpoint, so a
        // first host that passes validation says nothing about the others. Every
        // comma-bearing authority is refused instead of being partially checked.
        for (authority in listOf(
            "db.example.test:5432,attacker.example.test:5432",
            "127.0.0.1:5432,attacker.example.test:5432",
            "db.example.test:5432,other.example.test:6543",
            "DB.EXAMPLE.TEST:5432,ATTACKER.EXAMPLE.TEST:5432",
            "db.example.test:5432, attacker.example.test:5432",
            "db.example.test:5432,",
            ",db.example.test:5432",
            "db.example.test,attacker.example.test",
        )) {
            assertEquals(
                "multiple-hosts",
                rejected(valid + ("CHECK_URL" to "jdbc:postgresql://$authority/postgres")).code,
                authority,
            )
        }
    }

    @Test
    fun `ambiguous or encoded authorities are rejected rather than guessed`() {
        // A second colon is a port list, whitespace hides a second endpoint, and a
        // percent-encoded byte is an authority this tool cannot reason about by
        // inspection. None of them is interpreted.
        for (authority in listOf(
            "db.example.test:5432:6543",
            "db.example.test :5432",
            "db.example.test\t:5432",
            "db.example.test%2Cattacker.example.test",
            "db%2Eexample%2Etest:5432",
            "db.example.test:70000",
            "db.example.test:0",
            "[::1]:5432",
        )) {
            assertEquals(
                "malformed-url",
                rejected(valid + ("CHECK_URL" to "jdbc:postgresql://$authority/postgres")).code,
                authority,
            )
        }
    }

    @Test
    fun `a single host authority is still accepted`() {
        for (url in listOf(
            "jdbc:postgresql://db.example.test/postgres?sslmode=verify-full",
            "jdbc:postgresql://db.example.test:5432/postgres?sslmode=verify-full",
            "jdbc:postgresql://db.example.test:6543/postgres?sslmode=verify-full&",
            "jdbc:postgresql://127.0.0.1:6543/postgres?sslmode=disable",
        )) {
            assertEquals("checker", CheckSchemaConfig.resolve(valid + ("CHECK_URL" to url)).user, url)
        }
    }

    @Test
    fun `a host list rejection never echoes the hosts`() {
        val message = rejected(
            valid + ("CHECK_URL" to "jdbc:postgresql://db.example.test:5432,attacker.example.test:5432/postgres"),
        ).message!!
        assertFalse(message.contains("attacker.example.test"), "message leaked the host: $message")
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