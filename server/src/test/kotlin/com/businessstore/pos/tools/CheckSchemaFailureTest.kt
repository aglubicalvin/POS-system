package com.businessstore.pos.tools

import com.businessstore.pos.TestDb
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Failure behaviour of the REAL compiled CheckSchema tool.
 *
 * Every test launches a child JVM running `CheckSchema` (the class produced by
 * `compileProbeState`) and reads its actual stdout, stderr and exit code. Nothing
 * here re-implements the tool's error handling in Kotlin, so a regression in the
 * tool itself fails these tests.
 *
 * The point of the suite is what must NOT be printed: a pgJDBC failure names the
 * host and port it dialled, and an uncaught exception would add a stack trace. A
 * child process is the only way to observe the JVM's own output. No Supabase
 * connection is made; the only live database is the embedded PostgreSQL the suite
 * already uses.
 */
class CheckSchemaFailureTest {

    // ---------------------------------------------------------------------
    // P1: a host list is refused before a connection exists
    // ---------------------------------------------------------------------

    @Test
    fun `a multi host url is refused before any connection is attempted`() {
        // A listener stands in for the database: if the validator let the URL reach
        // the driver, pgJDBC would connect to this port before any identity check
        // could run.
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { listener ->
            listener.soTimeout = 250
            val accepted = AtomicInteger()
            val acceptor = thread(isDaemon = true) {
                while (!listener.isClosed) {
                    try {
                        listener.accept().use { accepted.incrementAndGet() }
                    } catch (_: java.net.SocketTimeoutException) {
                        // keep waiting
                    } catch (_: Exception) {
                        return@thread
                    }
                }
            }

            val run = runCheckSchema(
                mapOf(
                    "CHECK_URL" to
                        "jdbc:postgresql://127.0.0.1:${listener.localPort},127.0.0.1:${listener.localPort}/postgres",
                    "CHECK_USER" to "schema-checker",
                    "CHECK_PASS" to LOCAL_PASSWORD,
                ),
            )

            // Give a hypothetical stray connection a moment to be accepted.
            Thread.sleep(300)
            assertEquals(2, run.exitCode, run.describe())
            assertTrue(run.stderr.contains("CHECK_REJECTED code=multiple-hosts"), run.stderr)
            assertEquals(0, accepted.get(), "a rejected host list must not reach the network")
            assertSanitized(run, listOf(LOCAL_PASSWORD, "127.0.0.1", listener.localPort.toString()))
        }
    }

    @Test
    fun `pgJDBC dials a host that only appears later in a comma separated authority`() {
        // Measured against pgJDBC 42.7.14: with the first endpoint unreachable the
        // driver opens a socket to the SECOND endpoint in the list. That is the
        // failover the validator must not allow, and it is why the syntax is
        // refused outright rather than validating only the first host.
        val unreachable = closedPort()
        ServerSocket(0, 8, InetAddress.getLoopbackAddress()).use { second ->
            second.soTimeout = 1_500
            val dialled = AtomicInteger()
            val acceptor = thread(isDaemon = true) {
                while (!second.isClosed) {
                    try {
                        second.accept().use { dialled.incrementAndGet() }
                    } catch (_: java.net.SocketTimeoutException) {
                        // keep waiting
                    } catch (_: Exception) {
                        return@thread
                    }
                }
            }

            runCatching {
                DriverManager.getConnection(
                    "jdbc:postgresql://127.0.0.1:$unreachable,127.0.0.1:${second.localPort}/postgres" +
                        "?sslmode=disable&connectTimeout=1",
                    "schema-checker",
                    LOCAL_PASSWORD,
                )
            }

            val deadline = System.currentTimeMillis() + 5_000
            while (dialled.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue(dialled.get() > 0, "the driver never dialled the second endpoint of the host list")
        }
    }

    // ---------------------------------------------------------------------
    // P2: JDBC and query failures are sanitized
    // ---------------------------------------------------------------------

    @Test
    fun `a connection failure is sanitized and exits non-zero`() {
        val port = closedPort()
        val user = "schema-checker"
        val run = runCheckSchema(
            mapOf(
                "CHECK_URL" to "jdbc:postgresql://127.0.0.1:$port/postgres?sslmode=disable",
                "CHECK_USER" to user,
                "CHECK_PASS" to LOCAL_PASSWORD,
            ),
        )

        assertEquals(4, run.exitCode, run.describe())
        assertTrue(run.stderr.contains("CHECK_FAILED code=connection_failed"), run.stderr)
        assertTrue(run.stderr.contains("sqlstate=08001"), "expected the connection SQLState: ${run.stderr}")
        assertTrue(run.stdout.isEmpty(), "a failed check must not print a schema report: ${run.stdout}")
        assertSanitized(run, listOf(LOCAL_PASSWORD, user, "127.0.0.1", port.toString()))
    }

    @Test
    fun `a query failure on an established connection is sanitized and exits non-zero`() {
        // A REAL connection to the embedded server that completes authentication and
        // answers the identity query, then loses the socket exactly when the tool
        // sends its inventory query. The driver raises a genuine query-time I/O
        // failure, which is the case that used to escape main() as a stack trace.
        DropOnInventoryQuery(LOOPBACK, TestDb.port).use { proxy ->
            val run = runCheckSchema(
                mapOf(
                    "CHECK_URL" to "jdbc:postgresql://$LOOPBACK:${proxy.port}/postgres?sslmode=disable",
                    "CHECK_USER" to "postgres",
                    "CHECK_PASS" to LOCAL_PASSWORD,
                ),
            )

            assertEquals(5, run.exitCode, run.describe())
            assertTrue(run.stderr.contains("CHECK_FAILED code=query_failed"), run.stderr)
            assertTrue(
                Regex("sqlstate=[0-9A-Z]{5}").containsMatchIn(run.stderr),
                "expected a SQLState in the report: ${run.stderr}",
            )
            assertTrue(run.stdout.isEmpty(), "a failed check must not print a schema report: ${run.stdout}")
            assertSanitized(
                run,
                listOf(LOCAL_PASSWORD, "postgres", LOOPBACK, proxy.port.toString(), TestDb.port.toString()),
            )
        }
    }

    @Test
    fun `an identity mismatch is refused on the connected session`() {
        val run = runCheckSchema(
            mapOf(
                "CHECK_URL" to "jdbc:postgresql://$LOOPBACK:${TestDb.port}/postgres?sslmode=disable",
                "CHECK_USER" to "postgres",
                "CHECK_PASS" to "",
                "CHECK_PASS_ALLOW_EMPTY" to "yes",
                "CHECK_EXPECTED_USER" to "a_role_this_session_is_not",
            ),
        )

        assertEquals(3, run.exitCode, run.describe())
        assertTrue(run.stderr.contains("CHECK_REJECTED code=identity-mismatch"), run.stderr)
        assertFalse(run.stderr.contains("a_role_this_session_is_not"), run.stderr)
        assertTrue(run.stdout.isEmpty(), "no schema report when the identity is refused: ${run.stdout}")
    }

    @Test
    fun `a successful check still reports the schema`() {
        val database = "check_schema_ok_${shortId()}"
        var user: String
        TestDb.dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE $database") }
            user = conn.metaData.userName
        }
        try {
            val run = runCheckSchema(
                mapOf(
                    "CHECK_URL" to "jdbc:postgresql://$LOOPBACK:${TestDb.port}/$database?sslmode=disable",
                    "CHECK_USER" to user,
                    "CHECK_PASS" to "",
                    "CHECK_PASS_ALLOW_EMPTY" to "yes",
                ),
            )

            assertEquals(0, run.exitCode, run.describe())
            assertTrue(run.stdout.contains("base tables in public schema"), run.stdout)
            assertTrue(run.stdout.contains("missing canonical tables"), run.stdout)
            assertFalse(run.stderr.contains("CHECK_FAILED"), run.stderr)
            assertFalse(run.stderr.contains("CHECK_REJECTED"), run.stderr)
        } finally {
            TestDb.dataSource.connection.use { conn ->
                conn.createStatement().use { it.execute("DROP DATABASE IF EXISTS $database") }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------

    private data class Run(val exitCode: Int, val stdout: String, val stderr: String) {
        fun describe(): String = "exit=$exitCode stdout=<$stdout> stderr=<$stderr>"
    }

    /**
     * Launch the compiled tool with exactly these CHECK_* variables and capture
     * both streams. stderr is drained on its own thread so a driver that writes a
     * burst cannot deadlock the child while stdout is being read.
     */
    private fun runCheckSchema(env: Map<String, String>): Run {
        // CheckSchema.java deliberately has no package declaration, so it is loaded
        // from the default package exactly as `compileProbeState` produced it.
        val builder = ProcessBuilder("java", "-cp", toolClasspath(), "CheckSchema")
        val childEnv = builder.environment()
        childEnv.keys
            .filter { it.startsWith("CHECK_") }
            .toList()
            .forEach { childEnv.remove(it) }
        childEnv.putAll(env)

        val process = builder.start()
        val stderr = StringBuilder()
        val drain = thread(isDaemon = true) {
            process.errorStream.bufferedReader().forEachLine { line ->
                synchronized(stderr) { stderr.append(line).append('\n') }
            }
        }
        // A child that never exits must fail this test, not stall the whole suite.
        val finished = process.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val stdout = process.inputStream.bufferedReader().readText()
        val exitCode = if (finished) process.exitValue() else -1
        drain.join(5_000)
        return Run(exitCode, stdout, synchronized(stderr) { stderr.toString() })
    }

    /**
     * Everything that must never appear in a failure report. The password, the
     * user, the host and the port are all deliberately supplied through the
     * environment, so a leak would be attributable to the tool.
     */
    private fun assertSanitized(run: Run, secrets: List<String>) {
        val text = run.stderr
        for (secret in secrets) {
            assertFalse(text.contains(secret), "stderr leaked '$secret': $text")
        }
        assertFalse(text.contains("jdbc:postgresql://"), "stderr leaked the JDBC URL: $text")
        assertFalse(text.contains("\tat "), "stderr carried a stack trace: $text")
        assertFalse(text.lowercase().contains("exception"), "stderr named an exception type: $text")
        assertFalse(text.contains("org.postgresql"), "stderr exposed driver internals: $text")
    }

    /** The compiled tool classes plus the test runtime (which has pgJDBC). */
    private fun toolClasspath(): String {
        val classesDir = File(System.getProperty("user.dir"), "build/classes/java/probeState")
        check(classesDir.isDirectory) {
            "Compiled tool classes not found at $classesDir. Run './gradlew compileProbeState' first."
        }
        return listOf(classesDir.absolutePath, System.getProperty("java.class.path"))
            .joinToString(File.pathSeparator)
    }

    /**
     * A loopback TCP proxy in front of the embedded server.
     *
     * It forwards the whole handshake unchanged and then drops the connection as
     * soon as the client sends the inventory query. That produces a real
     * query-phase failure from the real driver on a connection that genuinely
     * succeeded, without depending on cluster privileges or on timing.
     */
    private class DropOnInventoryQuery(host: String, upstreamPort: Int) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort

        private val worker = thread(isDaemon = true) {
            try {
                server.accept().use { client ->
                    Socket(host, upstreamPort).use { upstream -> relay(client, upstream) }
                }
            } catch (_: Exception) {
                // The assertions in the test explain whatever happened.
            }
        }

        private fun relay(client: Socket, upstream: Socket) {
            // Server -> client is a straight copy, so the driver sees the real
            // authentication exchange and server parameters.
            thread(isDaemon = true) {
                runCatching { upstream.getInputStream().copyTo(client.getOutputStream()) }
            }
            val fromClient = DataInputStream(client.getInputStream().buffered())
            val toServer = DataOutputStream(upstream.getOutputStream().buffered())

            // Startup packets carry a length and protocol but no type byte. GSS and
            // TLS probes are forwarded too: the real server answers them.
            while (true) {
                val length = fromClient.readInt()
                val body = ByteArray(length - 4)
                fromClient.readFully(body)
                toServer.writeInt(length)
                toServer.write(body)
                toServer.flush()
                val protocol = ((body[0].toInt() and 0xFF) shl 24) or
                    ((body[1].toInt() and 0xFF) shl 16) or
                    ((body[2].toInt() and 0xFF) shl 8) or
                    (body[3].toInt() and 0xFF)
                if (protocol != SSL_REQUEST && protocol != GSSENC_REQUEST) break
            }

            // Afterwards the client speaks typed messages: 1 byte type, Int32 length.
            while (true) {
                val type = fromClient.read()
                if (type < 0) return
                val length = fromClient.readInt()
                val body = ByteArray(length - 4)
                fromClient.readFully(body)
                if (String(body, Charsets.UTF_8).contains("information_schema")) {
                    toServer.flush()
                    runCatching { client.close() }
                    runCatching { upstream.close() }
                    return
                }
                toServer.write(type)
                toServer.writeInt(length)
                toServer.write(body)
                toServer.flush()
            }
        }

        override fun close() {
            runCatching { server.close() }
            worker.join(2_000)
        }
    }

    /** A loopback port with nothing listening, so a connect is refused immediately. */
    private fun closedPort(): Int =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun shortId(): String = UUID.randomUUID().toString().replace("-", "").take(10)

    private companion object {
        /** Deliberately not a credential: it exists only to be searched for in output. */
        const val LOCAL_PASSWORD = "s3cr3t-not-a-real-password"

        /** Hard limit for a child JVM; a hang must fail the test. */
        const val CHILD_TIMEOUT_SECONDS = 90L

        // The embedded server listens on loopback; the tool requires a fully
        // qualified host, so it is addressed by its IPv4 literal.
        const val LOOPBACK = "127.0.0.1"

        /** Startup protocol codes the driver may probe with before the real one. */
        const val SSL_REQUEST = 80877103
        const val GSSENC_REQUEST = 80877104
    }
}
