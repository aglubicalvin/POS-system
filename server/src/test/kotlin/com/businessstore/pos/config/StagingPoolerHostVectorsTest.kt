package com.businessstore.pos.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Cross-language hostname validation vectors, Kotlin side.
 *
 * The table in server/tools/staging-pooler-host-vectors.txt is the shared
 * contract: this test, the TypeScript reference suite, the PowerShell suite and
 * the real ProbeState binary all assert the same inputs, so a grammar change in
 * one runtime fails that runtime rather than drifting until a workflow runs.
 */
class StagingPoolerHostVectorsTest {

    private data class Vector(val accept: Boolean, val value: String, val line: Int)

    private fun vectorFile(): File {
        val candidates = listOf(
            File("tools/staging-pooler-host-vectors.txt"),
            File("server/tools/staging-pooler-host-vectors.txt"),
            File("../server/tools/staging-pooler-host-vectors.txt"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("shared vector table not found; looked in ${candidates.map { it.absolutePath }}")
    }

    private fun vectors(): List<Vector> =
        vectorFile().readLines()
            .mapIndexed { index, text -> index + 1 to text }
            .filter { (_, text) -> text.isNotBlank() && !text.startsWith("#") }
            .map { (line, text) ->
                val separator = text.indexOf('\t')
                val decision = (if (separator < 0) text else text.substring(0, separator)).trim()
                val value = if (separator < 0) "" else text.substring(separator + 1)
                require(decision == "accept" || decision == "reject") {
                    "vector line $line has unknown decision: $decision"
                }
                Vector(accept = decision == "accept", value = value, line = line)
            }

    @Test
    fun `the shared table covers both directions`() {
        val all = vectors()
        assertTrue(all.size >= 25, "expected at least 25 vectors, found ${all.size}")
        assertTrue(all.count { it.accept } >= 3, "expected accepted vectors")
        assertTrue(all.count { !it.accept } >= 20, "expected rejected vectors")
    }

    @Test
    fun `Kotlin matches every shared vector`() {
        for (vector in vectors()) {
            val outcome = StagingPoolerHost.resolve(mapOf(StagingPoolerHost.ENV to vector.value))
            when (outcome) {
                is StagingPoolerHost.Outcome.Ok ->
                    assertTrue(
                        vector.accept,
                        "line ${vector.line}: Kotlin accepted a value the table rejects: " +
                            "<<${vector.value}>> as ${outcome.validated.host}",
                    )
                is StagingPoolerHost.Outcome.Rejected ->
                    assertFalse(
                        vector.accept,
                        "line ${vector.line}: Kotlin rejected a value the table accepts: " +
                            "<<${vector.value}>> (${outcome.code})",
                    )
            }
        }
    }

    @Test
    fun `an accepted hostname is rebuilt from validated parts`() {
        val outcome = StagingPoolerHost.resolve(
            mapOf(StagingPoolerHost.ENV to "  AWS-1-US-EAST-1.POOLER.SUPABASE.COM  "),
        )
        assertTrue(outcome is StagingPoolerHost.Outcome.Ok, "expected acceptance")
        val validated = (outcome as StagingPoolerHost.Outcome.Ok).validated
        assertEquals("aws-1-us-east-1.pooler.supabase.com", validated.host)
        assertEquals("1", validated.n)
        assertEquals("us-east-1", validated.region)
        assertEquals("pooler=aws-1-us-east-1.pooler.supabase.com", validated.describe())
    }

    @Test
    fun `a rejected value never echoes the raw hostname into the log message`() {
        val outcome = StagingPoolerHost.resolve(
            mapOf(StagingPoolerHost.ENV to "aws-0-eu-west-2.pooler.supabase.com.attacker.example"),
        )
        assertTrue(outcome is StagingPoolerHost.Outcome.Rejected)
        val message = (outcome as StagingPoolerHost.Outcome.Rejected).message
        assertFalse(message.contains("attacker.example"), "refusal must not echo the offered value")
    }
}
