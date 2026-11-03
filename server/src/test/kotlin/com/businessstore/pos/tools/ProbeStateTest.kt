package com.businessstore.pos.tools

import com.businessstore.pos.TestDb
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/**
 * Tests that exercise the ACTUAL ProbeState.java implementation.
 *
 * Each test launches a separate JVM running the compiled
 * `com.businessstore.pos.tools.ProbeState` class produced by the project's
 * `compileProbeState` Gradle task, pointed at a disposable embedded PostgreSQL
 * database. Nothing here simulates ProbeState's behaviour in Kotlin: the
 * assertions read the real tool's stdout, stderr and exit code.
 *
 * Credentials are passed only as environment variables, exactly as the
 * production workflow does it. No Supabase connection is ever made.
 */
class ProbeStateTest {

    // Synthetic references for tests. None is a real Supabase project.
    private val developmentRef = "uemdrtrzlvhdtonccczt"
    private val verificationRef = "numwzjogaanfhlrfgrqk"
    private val otherRef = "otherref00000000000z"

    private lateinit var dbName: String
    private lateinit var dbUser: String
    private lateinit var baseUrl: String

    @BeforeEach
    fun setUp() {
        dbName = "probe_state_test_${UUID.randomUUID().toString().replace("-", "")}"
        TestDb.dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE $dbName") }
            dbUser = conn.metaData.userName
            baseUrl = conn.metaData.url
        }
    }

    @AfterEach
    fun tearDown() {
        TestDb.dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("DROP DATABASE IF EXISTS $dbName") }
        }
    }

    /** JDBC URL pointing at this test's throwaway database. */
    private fun jdbcUrl(): String {
        val head = baseUrl.substringBefore('?')
        val tail = if (baseUrl.contains('?')) "?" + baseUrl.substringAfter('?') else ""
        return "${head.substringBeforeLast('/')}/$dbName$tail"
    }

    /**
     * Classpath for the child JVM: the compiled ProbeState classes plus the test
     * runtime classpath (which carries the PostgreSQL JDBC driver ProbeState
     * needs). Without the driver the child would fail with NoClassDefFoundError
     * instead of exercising the tool.
     */
    private fun probeClasspath(): String {
        val classesDir = File(
            System.getProperty("user.dir"),
            "build/classes/java/probeState",
        )
        check(classesDir.isDirectory) {
            "Compiled ProbeState classes not found at $classesDir. Run './gradlew compileProbeState' first."
        }
        val testRuntime = System.getProperty("java.class.path")
        return listOf(classesDir.absolutePath, testRuntime).joinToString(File.pathSeparator)
    }

    /** Launch the real ProbeState in a child JVM and capture its output. */
    private fun runProbeState(
        jdbcUrl: String,
        jdbcUser: String,
        jdbcPass: String = "",
        confirm: String = "yes",
    ): ProbeResult {
        val process = ProcessBuilder(
            "java",
            "-cp",
            probeClasspath(),
            "com.businessstore.pos.tools.ProbeState",
        )
            .apply {
                environment()["CHECK_URL"] = jdbcUrl
                environment()["CHECK_USER"] = jdbcUser
                environment()["CHECK_PASS"] = jdbcPass
                environment()["PROBE_CONFIRM"] = confirm
                redirectErrorStream(true)
            }
            .start()

        val combined = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return ProbeResult(exitCode, combined, jdbcPass)
    }

    data class ProbeResult(val exitCode: Int, val combined: String, val secret: String) {
        val lines: List<String> get() = combined.lines()

        private fun field(name: String): String? =
            lines.firstOrNull { it.trimStart().startsWith("$name=") }
                ?.substringAfter("=")
                ?.trim()

        val database: String? get() = field("database")
        val migrationHistory: String? get() = field("migration_history")
        val probeStatus: String? get() = field("probe_status")
        val errorCategory: String? get() = field("error_category")

        val host: String?
            get() = lines.firstOrNull { it.trimStart().startsWith("target host:") }
                ?.substringAfter(":")
                ?.trim()

        val isSuccess: Boolean get() = exitCode == 0
        val isRefused: Boolean get() = combined.contains("REFUSED")
        val isReadOnlyBanner: Boolean get() = combined.contains("read-only state probe")

        /** True when no credential material appears anywhere in the output. */
        val leaksNoSecret: Boolean
            get() = secret.isNotBlank() && !combined.contains(secret)
    }

    // ---- successful probe -------------------------------------------------

    @Test
    fun `probe connects, identifies the database and reports empty migration history`() {
        val result = runProbeState(jdbcUrl(), dbUser)

        assertEquals(0, result.exitCode, "probe must exit 0:\n${result.combined}")
        assertEquals(dbName, result.database, "probe must report the connected database")
        assertNotNull(result.host, "probe must report the target host")
        assertTrue(result.isReadOnlyBanner, "probe must announce its read-only mode")
        assertEquals("ok", result.probeStatus, "probe must report completion")
        assertEquals(
            "not_initialized",
            result.migrationHistory,
            "an empty database has no supabase_migrations.schema_migrations table",
        )
    }

    @Test
    fun `probe reports a populated migration history`() {
        executeOnTestDb(
            """
            CREATE SCHEMA IF NOT EXISTS supabase_migrations;
            CREATE TABLE supabase_migrations.schema_migrations (
                version bigint PRIMARY KEY,
                inserted_at timestamptz NOT NULL DEFAULT now()
            );
            INSERT INTO supabase_migrations.schema_migrations (version) VALUES (1);
            """.trimIndent(),
        )

        val result = runProbeState(jdbcUrl(), dbUser)

        assertEquals(0, result.exitCode, "probe must succeed:\n${result.combined}")
        assertTrue(
            result.migrationHistory?.startsWith("1 rows from supabase_migrations.schema_migrations") == true,
            "expected the row count, got: ${result.migrationHistory}",
        )
    }

    // ---- safety gates -----------------------------------------------------

    @Test
    fun `missing confirmation is refused before connecting`() {
        val result = runProbeState(jdbcUrl(), dbUser, confirm = "")

        assertEquals(3, result.exitCode, "probe must exit 3 without PROBE_CONFIRM=yes")
        assertTrue(result.isRefused, "probe must refuse:\n${result.combined}")
        assertTrue(result.combined.contains("PROBE_CONFIRM"), "the refusal must name the missing guard")
    }

    @Test
    fun `production host is refused even with confirmation`() {
        val result = runProbeState("jdbc:postgresql://prod-db.example.com:5432/postgres", "postgres")

        assertEquals(4, result.exitCode, "probe must exit 4 on a production host")
        assertTrue(result.isRefused, "probe must refuse:\n${result.combined}")
        assertTrue(
            result.combined.contains("production", ignoreCase = true),
            "the refusal must explain why",
        )
    }

    @Test
    fun `missing url is refused`() {
        val result = runProbeState("", dbUser)

        assertEquals(2, result.exitCode, "probe must exit 2 with no CHECK_URL")
        assertTrue(result.isRefused, "probe must refuse:\n${result.combined}")
    }

    // ---- username / reference rejection -----------------------------------

    @Test
    fun `development project username is rejected`() {
        val result = runProbeState(jdbcUrl(), "postgres.$developmentRef")

        assertFalse(result.isSuccess, "a development-project role must not produce a successful probe")
        assertTrue(result.isRefused || result.exitCode != 0, "expected a non-zero exit")
    }

    @Test
    fun `verification project username is rejected`() {
        val result = runProbeState(jdbcUrl(), "postgres.$verificationRef")

        assertFalse(result.isSuccess, "a verification-project role must not produce a successful probe")
        assertTrue(result.isRefused || result.exitCode != 0, "expected a non-zero exit")
    }

    @Test
    fun `mismatched username is rejected`() {
        val result = runProbeState(jdbcUrl(), "postgres.$otherRef")

        assertFalse(result.isSuccess, "a mismatched role must not produce a successful probe")
        assertTrue(result.isRefused || result.exitCode != 0, "expected a non-zero exit")
    }

    // ---- failure handling -------------------------------------------------

    @Test
    fun `connection failure is sanitized and never leaks the password`() {
        val secret = "sup3rs3cr3t-probe-pass"
        val result = runProbeState(
            "jdbc:postgresql://127.0.0.1:1/postgres",
            "postgres.mjqoydqcmecqjvzbckgk",
            jdbcPass = secret,
        )

        assertEquals(5, result.exitCode, "a connection failure must exit 5")
        assertEquals("error", result.probeStatus, "probe must report a failure status")
        assertEquals("connection_failed", result.errorCategory, "probe must report a sanitized category")
        assertFalse(result.combined.contains(secret), "the password must never appear in output")
        assertFalse(
            result.combined.lines().any { it.trimStart().startsWith("at ") },
            "no raw driver stack trace may be emitted",
        )
        assertFalse(
            result.combined.contains("mjqoydqcmecqjvzbckgk"),
            "the role reference must be scrubbed from failure output",
        )
    }

    // ---- read-only guarantees ---------------------------------------------

    @Test
    fun `probe does not create or alter roles`() {
        val before = roleNames()
        runProbeState(jdbcUrl(), dbUser)
        val after = roleNames()

        assertEquals(before, after, "probe must not create, alter or drop any role")
    }

    @Test
    fun `probe leaves existing rows untouched`() {
        // Seed a sentinel table in the throwaway database, run the probe, and prove
        // the rows are byte-for-byte unchanged. The probe only issues SELECTs.
        executeOnTestDb(
            """
            CREATE TABLE probe_sentinel (id int PRIMARY KEY, note text NOT NULL);
            INSERT INTO probe_sentinel (id, note) VALUES (1, 'must survive');
            INSERT INTO probe_sentinel (id, note) VALUES (2, 'must survive');
            """.trimIndent(),
        )

        val result = runProbeState(jdbcUrl(), dbUser)
        assertEquals(0, result.exitCode, "probe must succeed:\n${result.combined}")

        val rows = queryOnTestDb("SELECT id || '=' || note FROM probe_sentinel ORDER BY id")
        assertEquals(
            listOf("1=must survive", "2=must survive"),
            rows,
            "probe must not insert, update or delete any row",
        )
    }

    @Test
    fun `probe leaves table definitions untouched`() {
        executeOnTestDb("CREATE TABLE probe_sentinel (id int PRIMARY KEY)")

        runProbeState(jdbcUrl(), dbUser)

        val tables = queryOnTestDb(
            "SELECT table_name FROM information_schema.tables " +
                "WHERE table_schema='public' ORDER BY 1",
        )
        assertEquals(listOf("probe_sentinel"), tables, "probe must not create or drop tables")
    }

    private fun roleNames(): List<String> =
        TestDb.dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT rolname FROM pg_roles ORDER BY 1").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    /** Run DDL/DML against this test's throwaway database. */
    private fun executeOnTestDb(sql: String) {
        java.sql.DriverManager.getConnection(jdbcUrl(), dbUser, "").use { conn ->
            conn.createStatement().use { it.execute(sql) }
        }
    }

    /** Read a single text column from this test's throwaway database. */
    private fun queryOnTestDb(sql: String): List<String> =
        java.sql.DriverManager.getConnection(jdbcUrl(), dbUser, "").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }
}
