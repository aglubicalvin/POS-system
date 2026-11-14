package com.businessstore.pos.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The read-only gate, exercised against the REAL compiled ProbeState in a child
 * JVM, but with a fake JDBC driver instead of PostgreSQL.
 *
 * The fake driver is registered through `-Djdbc.drivers`, a standard JDBC
 * mechanism: no test-only environment variable, URL parameter or production
 * backdoor is added to the probe. It lets the three ways a server can fail to
 * confirm read-only mode (`off`, no row, a failed query) be produced
 * deterministically. Each must exit 6, report a sanitized error, and inspect no
 * catalog.
 *
 * It also covers the staging deny-list contract in the same child JVM, because
 * those refusals happen before any connection is opened.
 */
class ProbeStateReadOnlyTest {

    private val fakeUrl = ProbeReadOnlyFakeDriver.URL_PREFIX

    private fun probeClasspath(): String {
        val classesDir = File(System.getProperty("user.dir"), "build/classes/java/probeState")
        check(classesDir.isDirectory) {
            "Compiled ProbeState classes not found at $classesDir. Run './gradlew compileProbeState' first."
        }
        return listOf(classesDir.absolutePath, System.getProperty("java.class.path"))
            .joinToString(File.pathSeparator)
    }

    private data class Result(val exitCode: Int, val combined: String) {
        val lines: List<String> get() = combined.lines()
        fun field(name: String): String? =
            lines.firstOrNull { it.trimStart().startsWith("$name=") }?.substringAfter("=")?.trim()
    }

    /** Launch the real probe with the fake driver in front of pgJDBC. */
    private fun runProbe(
        mode: String,
        user: String = "postgres",
        url: String = fakeUrl,
        extraEnv: Map<String, String> = emptyMap(),
    ): Result {
        val process = ProcessBuilder(
            "java",
            "-cp",
            probeClasspath(),
            "-Djdbc.drivers=com.businessstore.pos.tools.ProbeReadOnlyFakeDriver",
            "-Dprobe.fake.mode=$mode",
            "com.businessstore.pos.tools.ProbeState",
        ).apply {
            environment()["CHECK_URL"] = url
            environment()["CHECK_USER"] = user
            environment()["CHECK_PASS"] = "not-a-real-password"
            environment()["PROBE_CONFIRM"] = "yes"
            environment().remove("POS_NON_STAGING_REFS")
            environment().putAll(extraEnv)
            redirectErrorStream(true)
        }.start()

        val combined = process.inputStream.bufferedReader().readText()
        return Result(process.waitFor(), combined)
    }

    private fun assertNotConfirmed(result: Result, mode: String) {
        assertEquals(6, result.exitCode, "mode=$mode must exit 6:\n${result.combined}")
        assertEquals("error", result.field("probe_status"), "mode=$mode must report an error status")
        assertEquals(
            "read_only_not_confirmed",
            result.field("error_category"),
            "mode=$mode must name the read-only category",
        )
        assertFalse(
            result.combined.contains("read_only_transaction=server_confirmed"),
            "mode=$mode must never claim server confirmation:\n${result.combined}",
        )
        assertFalse(
            result.combined.contains("app_tables="),
            "mode=$mode must not run the catalog audit:\n${result.combined}",
        )
        assertFalse(
            result.combined.contains("-- migration history --"),
            "mode=$mode must not inspect migration history:\n${result.combined}",
        )
        assertFalse(
            result.combined.contains("database="),
            "mode=$mode must not report a database:\n${result.combined}",
        )
        // The failure detail is reduced to the server's own short answer.
        assertTrue(
            result.field("error_detail")?.startsWith("transaction_read_only=") == true,
            "mode=$mode must report the sanitized server answer:\n${result.combined}",
        )
    }

    @Test
    fun `a server that reports off exits 6 and inspects nothing`() {
        assertNotConfirmed(runProbe("off"), "off")
    }

    @Test
    fun `a server that returns no row exits 6 and inspects nothing`() {
        assertNotConfirmed(runProbe("missing"), "missing")
    }

    @Test
    fun `a failed transaction_read_only query exits 6 and inspects nothing`() {
        val result = runProbe("throw")
        assertNotConfirmed(result, "throw")
        assertEquals(
            "transaction_read_only=unavailable",
            result.field("error_detail"),
            "a failed query must be reported without driver text:\n${result.combined}",
        )
        assertFalse(
            result.combined.contains("fixture refuses"),
            "the driver's message must not be echoed:\n${result.combined}",
        )
    }

    // ---- deny-list, in the same child JVM --------------------------------

    @Test
    fun `the built-in development reference is refused with a blank custom list`() {
        val result = runProbe(
            "off",
            user = "postgres.uemdrtrzlvhdtonccczt",
            extraEnv = mapOf("POS_NON_STAGING_REFS" to "   , "),
        )
        assertEquals(11, result.exitCode, result.combined)
        assertTrue(result.combined.contains("non-staging"), result.combined)
    }

    @Test
    fun `the built-in verification reference is refused with no custom list`() {
        val result = runProbe("off", user = "postgres.numwzjogaanfhlrfgrqk")
        assertEquals(11, result.exitCode, result.combined)
    }

    @Test
    fun `a custom reference is refused after trimming, lower-casing and de-duplication`() {
        val result = runProbe(
            "off",
            user = "postgres.customref00000000001",
            extraEnv = mapOf("POS_NON_STAGING_REFS" to "  CUSTOMREF00000000001 , customref00000000001 ,, "),
        )
        assertEquals(11, result.exitCode, result.combined)
    }

    @Test
    fun `a malformed custom entry is a refusal, not a silently disabled guard`() {
        val result = runProbe("off", extraEnv = mapOf("POS_NON_STAGING_REFS" to "not-a-ref"))
        assertEquals(11, result.exitCode, result.combined)
        assertTrue(result.combined.contains("POS_NON_STAGING_REFS"), result.combined)
        assertTrue(result.combined.contains("not a Supabase project reference"), result.combined)
    }
}
