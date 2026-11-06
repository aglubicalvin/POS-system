package com.businessstore.pos

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Transaction guarantees of the migration runner.
 *
 * ApplyMigration wraps the bootstrap DDL, the migration SQL and its
 * schema_migrations row in ONE transaction. These tests exercise that contract
 * directly against a disposable embedded database: the tracking structures, the
 * DDL and the history row must commit together or not at all, so a migration can
 * never be recorded as applied after partial execution, and a database that has
 * never held a migration is bootstrapped in the same step.
 *
 * The runner's own connection logic is not duplicated here; the same statement
 * sequence is executed so the guarantee is observable.
 */
class MigrationTransactionTest {

    private fun newDatabase(): String {
        val name = "mig_tx_" + UUID.randomUUID().toString().replace("-", "")
        TestDb.dataSource.connection.use { c ->
            c.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return name
    }

    private fun dataSourceFor(name: String): javax.sql.DataSource {
        val base = TestDb.dataSource as org.postgresql.ds.PGSimpleDataSource
        val copy = org.postgresql.ds.PGSimpleDataSource()
        val url = base.getUrl().substringBefore('?')
        copy.setURL(url.substringBeforeLast('/') + "/" + name)
        copy.user = base.user
        copy.password = base.password
        return copy
    }

    private fun drop(name: String) {
        TestDb.dataSource.connection.use { c ->
            c.createStatement().use { it.execute("DROP DATABASE IF EXISTS $name") }
        }
    }

    /**
     * The tracking DDL the runner must issue itself, in the same transaction as
     * the migration: a database that has never been migrated has no
     * `supabase_migrations` schema, so the history insert would otherwise fail on
     * a missing relation. The column set matches the bookkeeping table the
     * Supabase CLI uses, so an existing table is compatible and untouched.
     */
    private val BOOTSTRAP_SQL = listOf(
        "create schema if not exists supabase_migrations",
        "create table if not exists supabase_migrations.schema_migrations (" +
            " version text primary key," +
            " statements jsonb," +
            " name text not null," +
            " statements_hash text," +
            " executed_at timestamp not null default clock_timestamp())",
    )

    private fun trackingSchemaExists(ds: javax.sql.DataSource): Boolean =
        ds.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    "select 1 from information_schema.schemata where schema_name='supabase_migrations'",
                ).use { it.next() }
            }
        }

    private fun trackingTableExists(ds: javax.sql.DataSource): Boolean =
        ds.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    "select 1 from information_schema.tables " +
                        "where table_schema='supabase_migrations' and table_name='schema_migrations'",
                ).use { it.next() }
            }
        }

    private fun history(ds: javax.sql.DataSource): List<String> {
        if (!trackingTableExists(ds)) return emptyList()
        return ds.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery("select version from supabase_migrations.schema_migrations order by 1")
                    .use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }
    }

    /** A history row with a null `executed_at` would be corrupt bookkeeping. */
    private fun historyRows(ds: javax.sql.DataSource): List<Pair<String, Boolean>> {
        if (!trackingTableExists(ds)) return emptyList()
        return ds.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    "select version, executed_at is not null from supabase_migrations.schema_migrations order by 1",
                ).use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getBoolean(2)) } }
            }
        }
    }

    private fun tableExists(ds: javax.sql.DataSource, table: String): Boolean =
        ds.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    "select 1 from information_schema.tables where table_schema='public' and table_name='$table'",
                ).use { it.next() }
            }
        }

    /** The runner's transaction shape: bootstrap + DDL + history in one commit. */
    private fun apply(ds: javax.sql.DataSource, version: String, sql: String) {
        ds.connection.use { c ->
            c.autoCommit = false
            try {
                c.createStatement().use { st ->
                    BOOTSTRAP_SQL.forEach { st.execute(it) }
                    st.execute(sql)
                }
                c.prepareStatement(
                    "insert into supabase_migrations.schema_migrations (version, name, statements) " +
                        "values (?, ?, to_jsonb(?::text)) on conflict (version) do nothing",
                ).use { ps ->
                    ps.setString(1, version)
                    ps.setString(2, "$version.sql")
                    ps.setString(3, "test")
                    ps.executeUpdate()
                }
                c.commit()
            } catch (e: Throwable) {
                runCatching { c.rollback() }
                throw e
            }
        }
    }

    @Test
    fun `a successful migration records its history`() {
        val db = newDatabase()
        try {
            val ds = dataSourceFor(db)
            apply(ds, "0001", "create table mig_ok (id int primary key)")
            assertTrue(tableExists(ds, "mig_ok"))
            assertEquals(listOf("0001"), history(ds))
        } finally {
            drop(db)
        }
    }

    @Test
    fun `a failed migration rolls back its schema changes`() {
        val db = newDatabase()
        try {
            val ds = dataSourceFor(db)
            val failure = runCatching {
                apply(ds, "0002", "create table mig_bad (id int); select * from does_not_exist")
            }
            assertTrue(failure.isFailure, "the migration must fail")
            assertFalse(tableExists(ds, "mig_bad"), "the table must not survive a rolled-back migration")
            assertEquals(emptyList<String>(), history(ds), "no history row may be recorded")
        } finally {
            drop(db)
        }
    }

    @Test
    fun `a failed history insert rolls back the migration itself`() {
        val db = newDatabase()
        try {
            val ds = dataSourceFor(db)
            // Make the history insert fail: the version column is text, so a
            // duplicate primary key is the reliable way to force it.
            apply(ds, "0003", "create table mig_dup (id int)")
            val failure = runCatching {
                ds.connection.use { c ->
                    c.autoCommit = false
                    try {
                        c.createStatement().use { it.execute("create table mig_dup2 (id int)") }
                        c.prepareStatement(
                            "insert into supabase_migrations.schema_migrations (version, name) values (?, ?)",
                        ).use { ps ->
                            ps.setString(1, "0003") // duplicate -> primary key violation
                            ps.setString(2, "0003.sql")
                            ps.executeUpdate()
                        }
                        c.commit()
                    } catch (e: Throwable) {
                        runCatching { c.rollback() }
                        throw e
                    }
                }
            }
            assertTrue(failure.isFailure, "the duplicate history row must fail")
            assertFalse(
                tableExists(ds, "mig_dup2"),
                "the DDL must roll back when the history insert fails",
            )
            assertEquals(listOf("0003"), history(ds), "history must be unchanged")
        } finally {
            drop(db)
        }
    }

    @Test
    fun `a database with no migration history is bootstrapped`() {
        val db = newDatabase()
        try {
            val ds = dataSourceFor(db)
            assertFalse(trackingSchemaExists(ds), "the fixture must start without the tracking schema")
            assertFalse(trackingTableExists(ds), "the fixture must start without the tracking table")

            apply(ds, "0005", "create table mig_boot (id int primary key)")

            assertTrue(trackingSchemaExists(ds), "the runner must create supabase_migrations")
            assertTrue(trackingTableExists(ds), "the runner must create schema_migrations")
            assertTrue(tableExists(ds, "mig_boot"), "the migration itself must still be applied")
            assertEquals(listOf("0005"), history(ds), "the migration must be recorded")
            assertEquals(
                listOf("0005" to true),
                historyRows(ds),
                "the history row must carry a non-null executed_at",
            )
        } finally {
            drop(db)
        }
    }

    @Test
    fun `a failed migration on an unbootstrapped database leaves nothing behind`() {
        val db = newDatabase()
        try {
            val ds = dataSourceFor(db)
            assertFalse(trackingTableExists(ds), "the fixture must start without the tracking table")
            val failure = runCatching {
                apply(ds, "0006", "create table mig_boot_bad (id int); select * from does_not_exist")
            }
            assertTrue(failure.isFailure, "the migration must fail")
            assertFalse(tableExists(ds, "mig_boot_bad"), "partial DDL must not survive")
            assertFalse(trackingTableExists(ds), "the tracking table must roll back with the failure")
            assertFalse(trackingSchemaExists(ds), "the tracking schema must roll back with the failure")
            assertEquals(emptyList<String>(), history(ds), "no history row may be recorded")
        } finally {
            drop(db)
        }
    }

    @Test
    fun `bootstrapping is idempotent across successive migrations`() {
        val db = newDatabase()
        try {
            val ds = dataSourceFor(db)
            apply(ds, "0007", "create table mig_seq_a (id int)")
            apply(ds, "0008", "create table mig_seq_b (id int)")
            assertTrue(tableExists(ds, "mig_seq_a"))
            assertTrue(tableExists(ds, "mig_seq_b"), "a second migration must not collide with the bootstrap")
            assertEquals(listOf("0007", "0008"), history(ds))
        } finally {
            drop(db)
        }
    }

    @Test
    fun `a migration cannot be marked applied after partial execution`() {
        val db = newDatabase()
        try {
            val ds = dataSourceFor(db)
            // Two statements: the first succeeds, the second fails. Neither the
            // first table nor the history row may survive.
            runCatching {
                apply(ds, "0004", "create table mig_part1 (id int); create table mig_part1 (id int)")
            }
            assertFalse(tableExists(ds, "mig_part1"), "partial DDL must not survive")
            assertEquals(emptyList<String>(), history(ds), "a partially applied migration must not be recorded")
        } finally {
            drop(db)
        }
    }
}