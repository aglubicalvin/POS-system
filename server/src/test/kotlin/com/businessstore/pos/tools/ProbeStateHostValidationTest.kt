package com.businessstore.pos.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Real-binary tests for the target-recognition and identity gates of
 * `com.businessstore.pos.tools.ProbeState`.
 *
 * Every case launches the compiled ProbeState in a child JVM, exactly as the
 * workflow does, and asserts the process exit code and output. No Supabase
 * connection is ever made: refused targets exit before the driver is touched,
 * and an accepted pooler target stops at the missing staged certificate (exit 9)
 * before any socket is opened.
 *
 * The hostname vectors come from the shared table that the TypeScript, Kotlin and
 * PowerShell suites assert against.
 */
class ProbeStateHostValidationTest {

    private val stagingRef = "stagingref0000000001"
    private val developmentRef = "uemdrtrzlvhdtonccczt"
    private val verificationRef = "numwzjogaanfhlrfgrqk"

    private data class ProbeResult(val exitCode: Int, val output: String)

    private fun probeClasspath(): String {
        val classesDir = File(System.getProperty("user.dir"), "build/classes/java/probeState")
        check(classesDir.isDirectory) {
            "Compiled ProbeState classes not found at $classesDir. Run './gradlew compileProbeState' first."
        }
        return listOf(classesDir.absolutePath, System.getProperty("java.class.path"))
            .joinToString(File.pathSeparator)
    }

    private fun vectorFile(): File {
        val candidates = listOf(
            File("tools/staging-pooler-host-vectors.txt"),
            File("server/tools/staging-pooler-host-vectors.txt"),
            File("../server/tools/staging-pooler-host-vectors.txt"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("shared vector table not found; looked in ${candidates.map { it.absolutePath }}")
    }

    private data class Vector(val accept: Boolean, val value: String, val line: Int)

    private fun vectors(): List<Vector> =
        vectorFile().readLines()
            .mapIndexed { index, text -> index + 1 to text }
            .filter { (_, text) -> text.isNotBlank() && !text.startsWith("#") }
            .map { (line, text) ->
                val separator = text.indexOf('\t')
                val decision = (if (separator < 0) text else text.substring(0, separator)).trim()
                val value = if (separator < 0) "" else text.substring(separator + 1)
                Vector(accept = decision == "accept", value = value, line = line)
            }

    /**
     * Launch the real probe. Every staging variable is cleared first so a
     * developer's shell configuration can never make a test pass by accident, then
     * only the caller's values are set.
     */
    private fun runProbe(
        url: String,
        user: String,
        pass: String = "probe-test-pass",
        env: Map<String, String> = emptyMap(),
    ): ProbeResult {
        val process = ProcessBuilder(
            "java",
            "-cp",
            probeClasspath(),
            "com.businessstore.pos.tools.ProbeState",
        ).apply {
            val environment = environment()
            for (key in listOf(
                "POS_STAGING_POOLER_HOST",
                "POS_STAGING_DB_ROOT_CERT",
                "POS_STAGING_DB_ROOT_CERT_SHA256",
                "POS_STAGING_PROJECT_REF",
                "POS_NON_STAGING_REFS",
            )) {
                environment.remove(key)
            }
            environment["CHECK_URL"] = url
            environment["CHECK_USER"] = user
            environment["CHECK_PASS"] = pass
            environment["PROBE_CONFIRM"] = "yes"
            environment.putAll(env)
            redirectErrorStream(true)
        }.start()

        val output = process.inputStream.bufferedReader().readText()
        return ProbeResult(process.waitFor(), output)
    }

    private fun stagingEnv(poolerHost: String) = mapOf(
        "POS_STAGING_POOLER_HOST" to poolerHost,
        "POS_STAGING_PROJECT_REF" to stagingRef,
    )

    private fun stagingUrl(host: String) = "jdbc:postgresql://$host:5432/postgres"

    private fun stagingUser() = "postgres.$stagingRef"

    // ---- hostname grammar, against the shared vector table ------------------

    @Test
    fun `accepted pooler hosts pass host validation and stop at the missing certificate`() {
        for (vector in vectors().filter { it.accept }) {
            val canonical = vector.value.trim().lowercase()
            val result = runProbe(
                stagingUrl(canonical),
                stagingUser(),
                env = stagingEnv(vector.value),
            )
            assertEquals(
                9,
                result.exitCode,
                "line ${vector.line} (${vector.value}) must pass host validation and then be " +
                    "refused for the missing staged certificate:\n${result.output}",
            )
            assertTrue(
                result.output.contains("POS_STAGING_DB_ROOT_CERT"),
                "the refusal must name the missing certificate:\n${result.output}",
            )
        }
    }

    @Test
    fun `rejected pooler hosts are refused before any connection is attempted`() {
        for (vector in vectors().filter { !it.accept }) {
            val result = runProbe(
                stagingUrl("aws-0-eu-west-2.pooler.supabase.com"),
                stagingUser(),
                env = stagingEnv(vector.value),
            )
            assertEquals(
                8,
                result.exitCode,
                "line ${vector.line} (${vector.value}) must be refused with exit 8:\n${result.output}",
            )
            assertTrue(result.output.contains("REFUSED"), "expected a refusal:\n${result.output}")
            // A refusal happens before the probe header: no session, no inventory.
            assertFalse(
                result.output.contains("read-only state probe"),
                "a refused target must not reach the probe body:\n${result.output}",
            )
            assertFalse(
                result.output.contains("read_only_transaction="),
                "a refused target must not claim read-only enforcement:\n${result.output}",
            )
        }
    }

    @Test
    fun `a pooler host that disagrees with the nominated URL is refused`() {
        val result = runProbe(
            stagingUrl("aws-1-us-east-1.pooler.supabase.com"),
            stagingUser(),
            env = stagingEnv("aws-0-eu-west-2.pooler.supabase.com"),
        )
        assertEquals(8, result.exitCode, result.output)
        assertTrue(result.output.contains("does not match"), result.output)
    }

    // ---- loopback classification ------------------------------------------

    @Test
    fun `hostnames that merely start with 127 are remote and are refused`() {
        for (host in listOf(
            "127.db.attacker.example",
            "127.attacker.example",
            "127.0.0.1.attacker.example",
            "localhost.attacker.example",
            "localhost.",
            "0.0.0.0",
            "0.0.0.0.attacker.example",
            "127.0.0.256",
            "127.0.0.1.1",
            "0127.0.0.1",
        )) {
            val result = runProbe("jdbc:postgresql://$host:5432/postgres", "postgres")
            assertEquals(8, result.exitCode, "host $host must be refused:\n${result.output}")
            assertFalse(
                result.output.contains("read-only state probe"),
                "host $host must be refused before the probe body:\n${result.output}",
            )
        }
    }

    @Test
    fun `a genuine loopback test database is still accepted`() {
        // Port 1 is closed, so the driver fails immediately; reaching exit 5
        // proves the loopback target passed validation and a connection was
        // attempted, without any TLS material being required.
        val result = runProbe("jdbc:postgresql://127.0.0.1:1/postgres", "probe_local")
        assertEquals(5, result.exitCode, "loopback must still be accepted:\n${result.output}")
        assertTrue(result.output.contains("read-only state probe"), result.output)
        assertFalse(result.output.contains("read_only_transaction="), result.output)
    }

    @Test
    fun `an IPv6 loopback literal is recognised only in bracket notation`() {
        val bracketed = runProbe("jdbc:postgresql://[::1]:1/postgres", "probe_local")
        assertEquals(5, bracketed.exitCode, "bracketed ::1 must be a loopback target:\n${bracketed.output}")

        // Unbracketed, the host:port split is ambiguous, so the value is not
        // treated as loopback and is refused rather than guessed at.
        val unbracketed = runProbe("jdbc:postgresql://::1:5432/postgres", "probe_local")
        assertEquals(8, unbracketed.exitCode, unbracketed.output)
    }

    @Test
    fun `a complete bracketed IPv6 authority is accepted as loopback`() {
        // Port 1 is closed, so exit 5 proves the authority passed validation and
        // the driver was reached. These are the forms whose port is explicit.
        for (url in listOf(
            "jdbc:postgresql://[::1]:1/postgres",
            "jdbc:postgresql://[0:0:0:0:0:0:0:1]:1/postgres",
        )) {
            val result = runProbe(url, "probe_local")
            assertEquals(
                5,
                result.exitCode,
                "$url must pass validation and fail only at connect:\n${result.output}",
            )
            assertTrue(result.output.contains("read-only state probe"), result.output)
            assertFalse(result.output.contains("read_only_transaction="), result.output)
        }

        // The port is optional. On a host where 5432 is filtered the connect can
        // block, so these assert only that the target was ACCEPTED (the probe
        // body was reached) rather than pinning the failure mechanism.
        for (url in listOf(
            "jdbc:postgresql://[::1]/postgres",
            "jdbc:postgresql://[0:0:0:0:0:0:0:1]/postgres",
        )) {
            val result = runProbe(url, "probe_local")
            assertNotEquals(8, result.exitCode, "$url must not be refused:\n${result.output}")
            assertTrue(
                result.output.contains("read-only state probe"),
                "an omitted port must still be an accepted loopback target:\n${result.output}",
            )
        }
    }

    @Test
    fun `a malformed bracketed IPv6 authority is refused before the probe starts`() {
        // The whole authority is validated, not just the text inside the first
        // brackets: every case below must stop at exit 8, before the probe header
        // is printed and before DriverManager is reached.
        val rejected = listOf(
            "jdbc:postgresql://[::1]attacker.example/postgres",
            "jdbc:postgresql://[::1]attacker.example:5432/postgres",
            "jdbc:postgresql://[::1]junk:5432/postgres",
            "jdbc:postgresql://[::1]:invalid/postgres",
            "jdbc:postgresql://[::1]:0/postgres",
            "jdbc:postgresql://[::1]:65536/postgres",
            "jdbc:postgresql://[::1]:5432:extra/postgres",
            "jdbc:postgresql://[::1]:/postgres",
            "jdbc:postgresql://[::1/postgres",
            "jdbc:postgresql://[]:5432/postgres",
            "jdbc:postgresql://[gggg::1]:5432/postgres",
            "jdbc:postgresql://[::1]:5432,/postgres",
            "jdbc:postgresql://[::1]@attacker.example:5432/postgres",
            "jdbc:postgresql://::1:5432/postgres",
        )
        for (url in rejected) {
            val result = runProbe(url, "probe_local")
            assertEquals(8, result.exitCode, "$url must be refused with exit 8:\n${result.output}")
            assertTrue(result.output.contains("REFUSED"), "expected a refusal:\n${result.output}")
            assertFalse(
                result.output.contains("read-only state probe"),
                "$url must be refused before the probe body:\n${result.output}",
            )
            assertFalse(
                result.output.contains("read_only_transaction="),
                "$url must never report a confirmed read-only session:\n${result.output}",
            )
        }
    }

    // ---- URL structure: the classified host is the host the driver uses -----

    @Test
    fun `a host in the database path is never the classified host`() {
        // The '@' suffix belongs to the database name, not the authority. When the
        // parser cut the whole URL at its last '@', these were classified as
        // loopback while the untouched string still named a remote authority for
        // the driver to resolve.
        for (url in listOf(
            "jdbc:postgresql://attacker.example:5432/business@127.0.0.1",
            "jdbc:postgresql://attacker.example:5432/business?target=@127.0.0.1",
            "jdbc:postgresql://attacker.example:5432/business#@127.0.0.1",
            "jdbc:postgresql://attacker.example/business@localhost",
            "jdbc:postgresql://attacker.example:5432/business@[::1]:5432",
        )) {
            val result = runProbe(url, "probe_local")
            assertEquals(8, result.exitCode, "$url must be refused with exit 8:\n${result.output}")
            assertTrue(result.output.contains("REFUSED"), "expected a refusal:\n${result.output}")
            assertFalse(
                result.output.contains("read-only state probe"),
                "$url must be refused before DriverManager is reached:\n${result.output}",
            )
            assertFalse(
                result.output.contains("read_only_transaction="),
                "$url must never report a confirmed read-only session:\n${result.output}",
            )
        }
    }

    @Test
    fun `URL user-info is refused instead of stripped`() {
        // Credentials arrive as CHECK_USER and CHECK_PASS. An authority carrying
        // its own is ambiguous about which '@' ends the user-info, so it is
        // refused even when the text after it looks like a loopback host.
        for (url in listOf(
            "jdbc:postgresql://user:secret@127.0.0.1:5432/postgres",
            "jdbc:postgresql://postgres@localhost/postgres",
            "jdbc:postgresql://@127.0.0.1:5432/postgres",
        )) {
            val result = runProbe(url, "probe_local")
            assertEquals(8, result.exitCode, "$url must be refused:\n${result.output}")
            assertTrue(result.output.contains("user-info"), result.output)
            assertFalse(result.output.contains("read-only state probe"), result.output)
        }
    }

    @Test
    fun `driver host and port properties in the query string are refused`() {
        // pgJDBC parses query parameters into the same property map that carries
        // the authority, and its own canonical names (PGHOST, PGPORT) override
        // that authority. The 42.7.14 driver is the authority on which names
        // exist, so the probe refuses every caller query parameter instead of
        // tracking the name list.
        for (url in listOf(
            "jdbc:postgresql://127.0.0.1:5432/postgres?PGHOST=attacker.example",
            "jdbc:postgresql://127.0.0.1:5432/postgres?pghost=attacker.example",
            "jdbc:postgresql://127.0.0.1:5432/postgres?PGHOST=attacker.example&PGPORT=6543",
            "jdbc:postgresql://127.0.0.1:5432/postgres?PGPORT=6543",
            "jdbc:postgresql://127.0.0.1:5432/postgres?port=6543",
            "jdbc:postgresql://127.0.0.1:5432/postgres?host=attacker.example",
            "jdbc:postgresql://127.0.0.1:5432/postgres?hostaddr=203.0.113.9",
            "jdbc:postgresql://127.0.0.1:5432/postgres?socketFactory=com.example.Factory",
            "jdbc:postgresql://127.0.0.1:5432/postgres?socketFactoryArg=attacker.example",
            "jdbc:postgresql://127.0.0.1:5432/postgres?sslrootcert=attacker.pem",
            "jdbc:postgresql://127.0.0.1:5432/postgres?",
            "jdbc:postgresql://127.0.0.1:5432/postgres#PGHOST=attacker.example",
        )) {
            val result = runProbe(url, "probe_local")
            assertEquals(8, result.exitCode, "$url must be refused:\n${result.output}")
            assertTrue(result.output.contains("query string"), result.output)
            assertFalse(result.output.contains("read-only state probe"), result.output)
            assertFalse(result.output.contains("read_only_transaction="), result.output)
        }
    }

    @Test
    fun `a query string cannot turn an accepted loopback target into another host`() {
        // The same URL without the query is accepted (and fails to connect,
        // exit 5), so the refusal above is caused by the query alone and not by
        // the authority or the port.
        val accepted = runProbe("jdbc:postgresql://127.0.0.1:1/postgres", "probe_local")
        assertEquals(5, accepted.exitCode, accepted.output)
        assertTrue(accepted.output.contains("read-only state probe"), accepted.output)

        val refused = runProbe(
            "jdbc:postgresql://127.0.0.1:1/postgres?PGHOST=attacker.example",
            "probe_local",
        )
        assertEquals(8, refused.exitCode, refused.output)
        assertTrue(refused.output.contains("query string"), refused.output)
        assertFalse(refused.output.contains("read-only state probe"), refused.output)
    }

    @Test
    fun `an unsupported URL form is refused rather than interpreted`() {
        for (url in listOf(
            "postgresql://127.0.0.1:5432/postgres",
            "jdbc:postgres://127.0.0.1:5432/postgres",
            "jdbc:postgresql:127.0.0.1/postgres",
            "jdbc:postgresql:///postgres",
            "jdbc:postgresql://localhost:5432,attacker.example:5432/postgres",
            "jdbc:postgresql://127.0.0.1%40attacker.example:5432/postgres",
            "jdbc:postgresql://127.0.0.1 :5432/postgres",
        )) {
            val result = runProbe(url, "probe_local")
            assertEquals(8, result.exitCode, "$url must be refused with exit 8:\n${result.output}")
            assertFalse(result.output.contains("read-only state probe"), result.output)
            assertFalse(result.output.contains("read_only_transaction="), result.output)
        }
    }

    // ---- project identity --------------------------------------------------

    @Test
    fun `a staging connection requires an explicitly designated project`() {
        val result = runProbe(
            stagingUrl("aws-0-eu-west-2.pooler.supabase.com"),
            stagingUser(),
            env = mapOf("POS_STAGING_POOLER_HOST" to "aws-0-eu-west-2.pooler.supabase.com"),
        )
        assertEquals(11, result.exitCode, result.output)
        assertTrue(result.output.contains("POS_STAGING_PROJECT_REF"), result.output)
    }

    @Test
    fun `a valid pooler hostname cannot stand in for project identity`() {
        val result = runProbe(
            stagingUrl("aws-0-eu-west-2.pooler.supabase.com"),
            "postgres.otherref00000000000z",
            env = stagingEnv("aws-0-eu-west-2.pooler.supabase.com"),
        )
        assertEquals(11, result.exitCode, result.output)
        assertTrue(result.output.contains("does not belong to the designated staging project"), result.output)
        assertFalse(result.output.contains("read-only state probe"), result.output)
    }

    @Test
    fun `known non-staging project references are refused as staging targets`() {
        for (ref in listOf(developmentRef, verificationRef)) {
            val result = runProbe(
                stagingUrl("aws-0-eu-west-2.pooler.supabase.com"),
                "postgres.$ref",
                env = mapOf(
                    "POS_STAGING_POOLER_HOST" to "aws-0-eu-west-2.pooler.supabase.com",
                    "POS_STAGING_PROJECT_REF" to ref,
                ),
            )
            assertEquals(11, result.exitCode, "ref $ref must be refused:\n${result.output}")
        }
    }

    @Test
    fun `a non-staging administrator login is refused even on a loopback target`() {
        // Identity is not a property of the hostname: a development project's
        // login must not read a database through the local test path either.
        val result = runProbe(
            "jdbc:postgresql://127.0.0.1:1/postgres",
            "postgres.$developmentRef",
        )
        assertEquals(11, result.exitCode, result.output)
        assertTrue(result.output.contains("known non-staging project"), result.output)
    }

    @Test
    fun `a malformed project reference is refused`() {
        val result = runProbe(
            stagingUrl("aws-0-eu-west-2.pooler.supabase.com"),
            "postgres.short",
            env = mapOf(
                "POS_STAGING_POOLER_HOST" to "aws-0-eu-west-2.pooler.supabase.com",
                "POS_STAGING_PROJECT_REF" to "short",
            ),
        )
        assertEquals(11, result.exitCode, result.output)
    }
}
