package com.businessstore.pos

import com.businessstore.pos.db.Database
import com.businessstore.pos.db.Schema
import com.businessstore.pos.db.SchemaVerifier
import com.businessstore.pos.tools.LocalProvisionTarget
import com.businessstore.pos.tools.Provision
import com.businessstore.pos.tools.ProvisionTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/**
 * Regression tests for the review findings on PR #2.
 *
 * Covers: rollback-failure safety, connected-target identity, the remote endpoint
 * and TLS policy, and the separate loopback local provisioning path. Every
 * database is a disposable database in the embedded PostgreSQL from [TestDb]; no
 * Supabase connection is made anywhere in this file.
 */
class ProvisioningSafetyTest {

    private fun newEmptyDatabase(): String {
        val name = "psafety_" + UUID.randomUUID().toString().replace("-", "")
        TestDb.dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return name
    }

    private fun base(): PGSimpleDataSource = TestDb.dataSource as PGSimpleDataSource

    private fun dataSourceFor(name: String): PGSimpleDataSource {
        val src = PGSimpleDataSource()
        val url = base().getUrl().substringBefore('?')
        src.setURL(url.substringBeforeLast('/') + "/" + name)
        src.user = base().user
        src.password = base().password
        return src
    }

    private fun drop(name: String) {
        TestDb.dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("DROP DATABASE IF EXISTS $name") }
        }
    }

    private fun hasMigrationsSchema(ds: DataSource): Boolean =
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "select 1 from information_schema.schemata where schema_name='supabase_migrations'",
                ).use { it.next() }
            }
        }

    // ---- rollback FAILURE must never commit --------------------------------

    /**
     * A DataSource whose connections fail `rollback()` while still serving the
     * provisioning statements. This simulates a connection that lost its
     * transactional integrity mid-run -- the case where restoring autocommit
     * would COMMIT the half-applied schema.
     */
    /**
     * A DataSource whose connections fail rollback() while still serving the
     * provisioning statements. Simulates a connection that lost transactional
     * integrity mid-run -- the case where restoring autocommit would COMMIT the
     * half-applied schema.
     *
     * Implemented with java.lang.reflect.Proxy so every JDBC method except the
     * three we care about is forwarded untouched; hand-written forwarding is
     * brittle across driver versions.
     */
    private class RollbackFailingConnection(val inner: Connection) {
        @Volatile var failRollback = true
        @Volatile var autocommitEnabledAfterFailure = false
        @Volatile var closed = false

        val proxy: Connection = java.lang.reflect.Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "rollback" -> {
                    if (failRollback) throw SQLException("simulated rollback failure", "08006")
                    method.invoke(inner, *(args ?: emptyArray()))
                }
                "setAutoCommit" -> {
                    val want = args?.getOrNull(0) as? Boolean ?: false
                    // On a real JDBC connection false -> true COMMITS the open
                    // transaction, which is exactly what must never happen here.
                    if (want && failRollback) autocommitEnabledAfterFailure = true
                    method.invoke(inner, *(args ?: emptyArray()))
                }
                "close" -> {
                    closed = true
                    method.invoke(inner, *(args ?: emptyArray()))
                }
                "equals" -> inner === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(inner)
                "toString" -> "RollbackFailingConnection($inner)"
                else -> method.invoke(inner, *(args ?: emptyArray()))
            }
        } as Connection
    }

    private class RollbackFailingDataSource(private val delegate: PGSimpleDataSource) : DataSource {
        val tracking = mutableListOf<RollbackFailingConnection>()

        override fun getConnection(): Connection =
            RollbackFailingConnection(delegate.connection).also { tracking += it }.proxy

        override fun getConnection(username: String?, password: String?): Connection = getConnection()
        override fun getLogWriter(): java.io.PrintWriter? = delegate.logWriter
        override fun setLogWriter(out: java.io.PrintWriter?) = delegate.setLogWriter(out)
        override fun setLoginTimeout(seconds: Int) = delegate.setLoginTimeout(seconds)
        override fun getLoginTimeout(): Int = delegate.loginTimeout
        override fun getParentLogger(): java.util.logging.Logger = delegate.parentLogger
        override fun <T : Any?> unwrap(iface: Class<T>?): T = delegate.unwrap(iface)
        override fun isWrapperFor(iface: Class<*>?): Boolean = delegate.isWrapperFor(iface)
    }
    @Test
    fun `a failed rollback preserves the original error and never enables autocommit`() {
        val dbName = newEmptyDatabase()
        try {
            val failing = RollbackFailingDataSource(dataSourceFor(dbName))
            // Force the baseline DDL to fail part way through.
            dataSourceFor(dbName).connection.use { conn ->
                conn.createStatement().use { it.execute("create table public.sales (wrong integer)") }
            }

            val error = assertThrows<Throwable> { Schema.apply(failing) }

            // 1. The DDL failure is primary.
            assertNotNull(error.message)
            // The compat-schema line of schema.sql is the FIRST statement that
            // touches an existing table, so a deliberately incompatible "sales"
            // aborts there -- after create schema and create table already ran.
            assertTrue(
                error.message!!.contains("sale_id") || error.message!!.contains("already exists"),
                "expected the original DDL failure, got: ${error.message}",
            )
            // 2. The rollback failure is attached as suppressed.
            assertTrue(
                error.suppressed.any { it is SQLException && it.message?.contains("simulated rollback failure") == true },
                "rollback failure must be suppressed on the original error, suppressed=${error.suppressed.map { it.message }}",
            )
            // 3. Autocommit was never switched on for the still-active transaction.
            val conn = failing.tracking.single()
            assertFalse(
                conn.autocommitEnabledAfterFailure,
                "a failed rollback must never be followed by enabling autocommit (that would commit)",
            )
            // 4. The connection was closed/invalidated rather than returned.
            assertTrue(conn.closed, "an untrusted connection must be closed")
            // 5. Nothing claims success.
            assertFalse(hasMigrationsSchema(dataSourceFor(dbName)))
        } finally {
            drop(dbName)
        }
    }

    @Test
    fun `normal provisioning still commits after a successful transaction`() {
        val dbName = newEmptyDatabase()
        try {
            val failing = RollbackFailingDataSource(dataSourceFor(dbName))
            val created = Schema.apply(failing)
            assertTrue(created)
            assertEquals(listOf("0000"), dataSourceFor(dbName).connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("select version from supabase_migrations.schema_migrations").use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
            })
        } finally {
            drop(dbName)
        }
    }

    // ---- connected target identity ----------------------------------------

    private fun target(expected: String, port: Int = 5432) = ProvisionTarget.Target(
        jdbcUrl = "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:$port/postgres?sslmode=verify-full",
        user = "postgres.$expected",
        password = "x",
        expected = expected,
        host = "aws-0-eu-west-2.pooler.supabase.com",
        port = port,
        rootCertificatePath = "/tmp/prod-ca-2021.crt",
    )

    private fun session(
        db: String? = "postgres",
        user: String? = "postgres.abcdefghij0123456789",
        addr: String? = "203.0.113.10",
        port: Int? = 5432,
    ) = Provision.ConnectedIdentity(db, user, addr, port)

    @Test
    fun `identity is confirmed only when the session matches the expected target`() {
        val t = target("abcdefghij0123456789")
        assertNull(Provision.identityProblem(session(), t), "a fully matching session must pass")
    }

    @Test
    fun `a session logged into a different project is rejected`() {
        val t = target("abcdefghij0123456789")
        val problem = Provision.identityProblem(session(user = "postgres.zyxwvutsrq9876543210"), t)
        assertEquals("target_mismatch", problem?.first)
    }

    @Test
    fun `a non-pooler session does not falsely confirm a project identity`() {
        val t = target("abcdefghij0123456789")
        val problem = Provision.identityProblem(session(user = "postgres"), t)
        assertNull(problem, "no suffix is not a mismatch")
        assertTrue(Provision.identityWeakness(session(user = "postgres")))
    }

    @Test
    fun `a socket session without a server address is not used as an identity signal`() {
        // inet_server_addr() being empty proves nothing either way; it is simply
        // not part of the verdict any more.
        val t = target("abcdefghij0123456789")
        assertNull(Provision.identityProblem(session(addr = ""), t))
    }

    @Test
    fun `the reported server port is not treated as the client-facing endpoint`() {
        // inet_server_port() reports the port the SERVER accepted on. Behind a
        // shared pooler that is the backend socket, not the 6543 the client
        // dialled, so it must not be compared to the configured pooler port.
        val t = target("abcdefghij0123456789", port = 6543)
        assertNull(
            Provision.identityProblem(session(port = 5432), t),
            "a differing server-side port must not fail verification on its own",
        )
    }

    @Test
    fun `a session without a project suffix is allowed but reported as weak`() {
        // A shared pooler may not surface the original login in current_user.
        // That is not fatal -- TLS is the identity guarantee -- but it must be
        // reported rather than claimed as a session-confirmed project.
        val t = target("abcdefghij0123456789")
        val shared = session(user = "authenticator")
        assertNull(Provision.identityProblem(shared, t))
        assertTrue(Provision.identityWeakness(shared), "a non-suffixed login must be reported as weak")
        assertFalse(Provision.identityWeakness(session()), "a suffixed login is not weak")
    }

    @Test
    fun `a session on a non-postgres database is rejected`() {
        val t = target("abcdefghij0123456789")
        val problem = Provision.identityProblem(session(db = "postgres_dev"), t)
        assertEquals("unexpected_database", problem?.first)
    }

    // ---- remote endpoint and TLS policy -----------------------------------

    /** A real file to satisfy the pinned-certificate check; contents are never parsed here. */
    private fun certFile(): String {
        val file = java.io.File.createTempFile("fake-root", ".crt")
        file.deleteOnExit()
        file.writeText("-----BEGIN CERTIFICATE-----\nnot-a-real-certificate\n-----END CERTIFICATE-----\n")
        return file.absolutePath
    }

    private fun remoteEnv(
        host: String = "aws-0-eu-west-2.pooler.supabase.com",
        port: Int = 5432,
        ssl: String = "?sslmode=verify-full",
        cert: String? = null,
    ) = buildMap {
        put(ProvisionTarget.CONFIRM_ENV, "yes")
        put(ProvisionTarget.URL_ENV, "jdbc:postgresql://$host:$port/postgres$ssl")
        put(ProvisionTarget.USER_ENV, "postgres.abcdefghij0123456789")
        put(ProvisionTarget.PASS_ENV, "not-a-real-password")
        put(ProvisionTarget.EXPECTED_ENV, "abcdefghij0123456789")
        put(ProvisionTarget.ROOT_CERT_ENV, cert ?: certFile())
    }

    private fun rejected(env: Map<String, String>): ProvisionTarget.Outcome.Rejected {
        val outcome = ProvisionTarget.resolve(env)
        assertTrue(outcome is ProvisionTarget.Outcome.Rejected, "expected rejection, got $outcome")
        return outcome as ProvisionTarget.Outcome.Rejected
    }

    @Test
    fun `a matching username on an untrusted host cannot proceed`() {
        // The configuration is internally consistent -- username suffix equals the
        // expected reference -- but the endpoint is not a Supabase pooler.
        val code = rejected(remoteEnv(host = "db.example.test")).code
        assertEquals("untrusted-endpoint", code)
    }

    @Test
    fun `an ip literal host cannot proceed`() {
        assertEquals("untrusted-endpoint", rejected(remoteEnv(host = "203.0.113.10")).code)
    }

    @Test
    fun `a lookalike supabase host cannot proceed`() {
        assertEquals(
            "untrusted-endpoint",
            rejected(remoteEnv(host = "aws-0-eu-west-2.pooler.supabase.com.evil.test")).code,
        )
    }

    @Test
    fun `a non-pooler port cannot proceed`() {
        assertEquals("untrusted-endpoint", rejected(remoteEnv(port = 5433)).code)
    }

    @Test
    fun `a remote target without TLS cannot proceed`() {
        assertEquals("tls-strong-verification-required", rejected(remoteEnv(ssl = "")).code)
        assertEquals("tls-strong-verification-required", rejected(remoteEnv(ssl = "?sslmode=disable")).code)
    }

    @Test
    fun `sslmode=require is rejected because it verifies neither chain nor host`() {
        // require encrypts but accepts any certificate: a MITM could present its
        // own and collect the administrator credential.
        assertEquals("tls-strong-verification-required", rejected(remoteEnv(ssl = "?sslmode=require")).code)
    }

    @Test
    fun `sslmode=verify-ca is rejected because it does not verify the hostname`() {
        assertEquals("tls-strong-verification-required", rejected(remoteEnv(ssl = "?sslmode=verify-ca")).code)
    }

    @Test
    fun `a pinned root certificate is required`() {
        val withoutCert = remoteEnv() - ProvisionTarget.ROOT_CERT_ENV
        assertEquals("missing-root-certificate", rejected(withoutCert).code)
        assertEquals(
            "missing-root-certificate",
            rejected(remoteEnv(cert = "/nonexistent/does-not-exist.crt")).code,
        )
    }

    @Test
    fun `a valid remote target is accepted`() {
        val outcome = ProvisionTarget.resolve(remoteEnv())
        assertTrue(outcome is ProvisionTarget.Outcome.Ok, "expected acceptance, got $outcome")
        val describe = (outcome as ProvisionTarget.Outcome.Ok).target.describe()
        assertFalse(describe.contains("pooler.supabase.com"), "describe() must not print the host")
        assertFalse(describe.contains("not-a-real-password"))
    }

    // ---- local provisioning path ------------------------------------------

    private fun localEnv(
        url: String = "jdbc:postgresql://localhost:5432/business_store",
        confirm: String? = "yes",
        user: String? = "business_store",
    ): Map<String, String> {
        val env = mutableMapOf(
            LocalProvisionTarget.URL_ENV to url,
            LocalProvisionTarget.PASS_ENV to "local-only",
        )
        if (confirm != null) env[LocalProvisionTarget.CONFIRM_ENV] = confirm
        if (user != null) env[LocalProvisionTarget.USER_ENV] = user
        return env
    }

    private fun localRejected(env: Map<String, String>): LocalProvisionTarget.Outcome.Rejected {
        val outcome = LocalProvisionTarget.resolve(env)
        assertTrue(outcome is LocalProvisionTarget.Outcome.Rejected, "expected rejection, got $outcome")
        return outcome as LocalProvisionTarget.Outcome.Rejected
    }

    @Test
    fun `local provisioning requires its own explicit confirmation`() {
        assertEquals("missing-confirmation", localRejected(localEnv(confirm = null)).code)
        for (bad in listOf("", "YES", "true", "1")) {
            assertEquals("missing-confirmation", localRejected(localEnv(confirm = bad)).code, "value '$bad'")
        }
    }

    @Test
    fun `local provisioning accepts loopback with confirmation`() {
        for (host in listOf("localhost", "127.0.0.1", "[::1]")) {
            val outcome = LocalProvisionTarget.resolve(localEnv(url = "jdbc:postgresql://$host:5432/business_store"))
            assertTrue(outcome is LocalProvisionTarget.Outcome.Ok, "host $host must be accepted, got $outcome")
        }
    }

    @Test
    fun `local provisioning rejects a remote host`() {
        val code = localRejected(localEnv(url = "jdbc:postgresql://db.example.test:5432/business_store")).code
        assertEquals("non-loopback-host", code)
    }

    @Test
    fun `local provisioning rejects a supabase pooler host`() {
        val code = localRejected(
            localEnv(url = "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres"),
        ).code
        assertEquals("non-loopback-host", code)
    }

    @Test
    fun `local provisioning requires a url and a user`() {
        assertEquals("missing-url", localRejected(localEnv(url = "")).code)
        assertEquals("missing-user", localRejected(localEnv(user = null)).code)
    }

    @Test
    fun `the two provisioning paths stay separate`() {
        // The remote path never accepts a loopback target...
        assertTrue(
            ProvisionTarget.resolve(localEnv() + (ProvisionTarget.URL_ENV to "jdbc:postgresql://localhost:5432/postgres"))
                is ProvisionTarget.Outcome.Rejected,
        )
        // ...and the local path never accepts the local confirmation for a remote host.
        assertEquals(
            "non-loopback-host",
            localRejected(
                localEnv(
                    url = "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres",
                ),
            ).code,
        )
    }

    @Test
    fun `local provisioning applies the same baseline and records 0000`() {
        val dbName = newEmptyDatabase()
        try {
            val ds = dataSourceFor(dbName)
            assertTrue(Schema.apply(ds))
            val result = SchemaVerifier.inspect(ds)
            assertTrue(result.provisioned, result.describe())
            SchemaVerifier.requireProvisioned(ds) // startup now accepts it
        } finally {
            drop(dbName)
        }
    }
}