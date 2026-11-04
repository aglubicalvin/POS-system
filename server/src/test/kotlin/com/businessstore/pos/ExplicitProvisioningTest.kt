package com.businessstore.pos

import com.businessstore.pos.db.Database
import com.businessstore.pos.db.Schema
import com.businessstore.pos.db.SchemaNotProvisionedException
import com.businessstore.pos.db.SchemaVerifier
import com.businessstore.pos.tools.ProvisionTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * Safety properties of the explicit-provisioning architecture.
 *
 * Two behaviours are under test and they must never blur:
 *   - normal startup VERIFIES the schema (SELECTs only) and refuses to run when
 *     the baseline is absent, and
 *   - provisioning CREATES it, only after a confirmed, explicitly targeted run.
 *
 * Every database here is a disposable database inside the embedded PostgreSQL
 * started by [TestDb]; each test gets its own empty database so "unprovisioned"
 * really means unprovisioned.
 */
class ExplicitProvisioningTest {

    private lateinit var dbName: String

    private fun newEmptyDatabase(): String {
        val name = "provision_test_" + UUID.randomUUID().toString().replace("-", "")
        TestDb.dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return name
    }

    /** DataSource for one disposable database, with its own connection. */
    private fun dataSourceFor(name: String): javax.sql.DataSource {
        val base = requireNotNull(TestDb.dataSource as? org.postgresql.ds.PGSimpleDataSource)
        val copy = org.postgresql.ds.PGSimpleDataSource()
        val jdbcUrl = base.getUrl().substringBefore('?')
        copy.setURL(jdbcUrl.substringBeforeLast('/') + "/" + name)
        copy.user = base.user
        copy.password = base.password
        return copy
    }

    private fun drop(name: String) {
        TestDb.dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute("DROP DATABASE IF EXISTS $name") }
        }
    }

    private fun hasMigrationsSchema(ds: javax.sql.DataSource): Boolean =
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "select 1 from information_schema.schemata where schema_name='supabase_migrations'",
                ).use { it.next() }
            }
        }

    private fun baselineRows(ds: javax.sql.DataSource): List<String> =
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "select version from supabase_migrations.schema_migrations order by version",
                ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }

    // ---- normal startup is read-only ---------------------------------------

    @Test
    fun `startup verification on an empty database fails with an actionable message`() {
        dbName = newEmptyDatabase()
        try {
            val ds = dataSourceFor(dbName)
            val error = assertThrows<SchemaNotProvisionedException> {
                SchemaVerifier.requireProvisioned(ds)
            }
            assertTrue(error.message!!.contains("not provisioned"), error.message)
            assertTrue(error.message!!.contains("provisionDatabase"), "message must name the task")
            assertTrue(error.message!!.contains("POS_DATABASE_URL"), "message must name the variable")
        } finally {
            drop(dbName)
        }
    }

    @Test
    fun `startup verification does not create the migrations schema or table`() {
        dbName = newEmptyDatabase()
        try {
            val ds = dataSourceFor(dbName)
            assertThrows<SchemaNotProvisionedException> { SchemaVerifier.requireProvisioned(ds) }
            assertFalse(
                hasMigrationsSchema(ds),
                "verification must not create supabase_migrations",
            )
        } finally {
            drop(dbName)
        }
    }

    @Test
    fun `startup verification records no migration history`() {
        dbName = newEmptyDatabase()
        try {
            val ds = dataSourceFor(dbName)
            assertThrows<SchemaNotProvisionedException> { SchemaVerifier.requireProvisioned(ds) }
            // No table at all, so there is nothing to record into; prove the
            // absence rather than merely the missing row.
            assertFalse(hasMigrationsSchema(ds))
        } finally {
            drop(dbName)
        }
    }

    @Test
    fun `startup verification passes on a provisioned database and changes nothing`() {
        dbName = newEmptyDatabase()
        try {
            val ds = dataSourceFor(dbName)
            assertEquals(true, Schema.apply(ds), "first apply must report creation")
            val before = baselineRows(ds)
            SchemaVerifier.requireProvisioned(ds) // must not throw
            assertEquals(before, baselineRows(ds), "verification must not alter history")
            assertEquals(listOf("0000"), before)
        } finally {
            drop(dbName)
        }
    }

    @Test
    fun `startup verification reports a missing baseline when the schema exists`() {
        dbName = newEmptyDatabase()
        try {
            val ds = dataSourceFor(dbName)
            ds.connection.use { conn ->
                conn.createStatement().use {
                    it.execute("create schema supabase_migrations")
                    it.execute(
                        "create table supabase_migrations.schema_migrations (" +
                            "version text primary key, name text not null)",
                    )
                }
            }
            val result = SchemaVerifier.inspect(ds)
            assertTrue(result.migrationsSchemaPresent)
            assertTrue(result.migrationsTablePresent)
            assertFalse(result.baselineRecorded, "an empty history is not a provisioned baseline")
            assertThrows<SchemaNotProvisionedException> { SchemaVerifier.requireProvisioned(ds) }
        } finally {
            drop(dbName)
        }
    }

    // ---- provisioning behaviour --------------------------------------------

    @Test
    fun `baseline can be provisioned and records version 0000`() {
        dbName = newEmptyDatabase()
        try {
            val ds = dataSourceFor(dbName)
            assertEquals(true, Schema.apply(ds))
            assertEquals(listOf("0000"), baselineRows(ds))
            val result = SchemaVerifier.inspect(ds)
            assertTrue(result.provisioned, result.describe())
        } finally {
            drop(dbName)
        }
    }

    @Test
    fun `repeated provisioning is idempotent and does not duplicate the baseline`() {
        dbName = newEmptyDatabase()
        try {
            val ds = dataSourceFor(dbName)
            assertEquals(true, Schema.apply(ds), "first run creates")
            assertEquals(false, Schema.apply(ds), "second run must report 'already provisioned'")
            assertEquals(listOf("0000"), baselineRows(ds), "exactly one baseline row")
        } finally {
            drop(dbName)
        }
    }

    @Test
    fun `failed provisioning rolls back and leaves no partial schema`() {
        dbName = newEmptyDatabase()
        try {
            val ds = dataSourceFor(dbName)
            // A table named like the canonical one, but with an incompatible
            // shape, makes the baseline DDL fail midway. Whatever happens, no
            // supabase_migrations schema may survive a rolled-back run.
            ds.connection.use { conn ->
                conn.createStatement().use { it.execute("create table public.sales (wrong integer)") }
            }
            val failure = runCatching { Schema.apply(ds) }
            assertTrue(failure.isFailure, "provisioning must fail against an incompatible schema")
            assertFalse(
                hasMigrationsSchema(ds),
                "a failed provisioning must roll back, leaving no supabase_migrations schema",
            )
        } finally {
            drop(dbName)
        }
    }

    @Test
    fun `schema and baseline are applied in one transaction`() {
        dbName = newEmptyDatabase()
        try {
            val ds = dataSourceFor(dbName)
            // Observe the transaction boundary directly: during apply, another
            // session must not see the migrations schema before commit.
            ds.connection.use { conn ->
                conn.autoCommit = false
                conn.createStatement().use { it.execute("create schema supabase_migrations") }
                // Uncommitted: the data source's own inspection uses a separate
                // connection, so it must not see this schema yet.
                val other = dataSourceFor(dbName)
                assertFalse(
                    hasMigrationsSchema(other),
                    "an uncommitted schema must not be visible to another session",
                )
                conn.rollback()
            }
        } finally {
            drop(dbName)
        }
    }

    // ---- provisioning configuration (fail closed, no connection) ------------

    private val validEnv = mapOf(
        ProvisionTarget.CONFIRM_ENV to "yes",
        ProvisionTarget.URL_ENV to "jdbc:postgresql://aws-0-eu-west-2.pooler.supabase.com:5432/postgres?sslmode=require",
        ProvisionTarget.USER_ENV to "postgres.abcdefghij0123456789",
        ProvisionTarget.PASS_ENV to "not-a-real-password",
        ProvisionTarget.EXPECTED_ENV to "abcdefghij0123456789",
    )

    private fun reject(env: Map<String, String>): ProvisionTarget.Outcome.Rejected {
        val outcome = ProvisionTarget.resolve(env)
        assertTrue(outcome is ProvisionTarget.Outcome.Rejected, "expected rejection, got $outcome")
        return outcome as ProvisionTarget.Outcome.Rejected
    }

    @Test
    fun `missing confirmation is rejected`() {
        assertEquals("missing-confirmation", reject(validEnv - ProvisionTarget.CONFIRM_ENV).code)
    }

    @Test
    fun `any confirmation other than exactly yes is rejected`() {
        for (bad in listOf("", "YES", "Yes", "y", "true", "1", "yes ")) {
            val code = reject(validEnv + (ProvisionTarget.CONFIRM_ENV to bad)).code
            assertEquals("missing-confirmation", code, "value '$bad' must not be accepted")
        }
    }

    @Test
    fun `missing database url is rejected`() {
        assertEquals("missing-url", reject(validEnv - ProvisionTarget.URL_ENV).code)
    }

    @Test
    fun `malformed database url is rejected`() {
        for (bad in listOf(
            "postgresql://db.example.test:5432/postgres",
            "jdbc:postgresql:///postgres",
            "jdbc:postgresql://localhost/postgres",
            "jdbc:mysql://db.example.test/postgres",
        )) {
            val code = reject(validEnv + (ProvisionTarget.URL_ENV to bad)).code
            assertTrue(code == "malformed-url", "URL '$bad' produced $code")
        }
    }

    @Test
    fun `credentials embedded in the url are rejected`() {
        val code = reject(
            validEnv + (ProvisionTarget.URL_ENV to "jdbc:postgresql://user:secret@db.example.test/postgres"),
        ).code
        assertEquals("credentials-in-url", code)
    }

    @Test
    fun `missing expected target is rejected`() {
        assertEquals("missing-expected-target", reject(validEnv - ProvisionTarget.EXPECTED_ENV).code)
    }

    @Test
    fun `a target identity mismatch is rejected`() {
        val code = reject(validEnv + (ProvisionTarget.EXPECTED_ENV to "zyxwvutsrq9876543210")).code
        assertEquals("target-mismatch", code)
    }

    @Test
    fun `a non-pooler username cannot prove its target and is rejected`() {
        assertEquals(
            "malformed-user",
            reject(validEnv + (ProvisionTarget.USER_ENV to "postgres")).code,
        )
    }

    @Test
    fun `a database other than postgres is rejected`() {
        val code = reject(
            validEnv + (ProvisionTarget.URL_ENV to "jdbc:postgresql://db.example.test:5432/appdb"),
        ).code
        assertEquals("unexpected-database", code)
    }

    @Test
    fun `a fully specified environment is accepted without exposing the password`() {
        val outcome = ProvisionTarget.resolve(validEnv)
        assertTrue(outcome is ProvisionTarget.Outcome.Ok, "expected acceptance, got $outcome")
        val target = (outcome as ProvisionTarget.Outcome.Ok).target
        val described = target.describe()
        assertFalse(described.contains("not-a-real-password"), "describe() must not print the password")
        assertFalse(described.contains("pooler.supabase.com"), "describe() must not print the host")
        assertTrue(described.contains("database=postgres"))
    }

    @Test
    fun `rejection messages never echo the password or the url`() {
        val messages = listOf(
            reject(validEnv - ProvisionTarget.EXPECTED_ENV).message,
            reject(validEnv + (ProvisionTarget.EXPECTED_ENV to "zyxwvutsrq9876543210")).message,
            reject(validEnv + (ProvisionTarget.URL_ENV to "jdbc:postgresql://u:p@h.example.test/postgres")).message,
            reject(validEnv - ProvisionTarget.CONFIRM_ENV).message,
        )
        for (message in messages) {
            assertFalse(message.contains("not-a-real-password"), "message leaked the password: $message")
            assertFalse(message.contains("pooler.supabase.com"), "message leaked the host: $message")
        }
    }
}