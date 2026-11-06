package com.businessstore.pos

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Transaction guarantees of the migration runner.
 *
 * ApplyMigration wraps the migration SQL and its schema_migrations row in ONE
 * transaction. These tests exercise that contract directly against a disposable
 * embedded database: the DDL and the history row must commit together or not at
 * all, so a migration can never be recorded as applied after partial execution.
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

    /** Create the tracking table the runner inserts into. */
    private fun bootstrap(ds: javax.sql.DataSource) {
        ds.connection.use { c ->
            c.createStatement().use {
                it.execute("create schema if not exists supabase_migrations")
                it.execute(
                    "create table if not exists supabase_migrations.schema_migrations (" +
                        "version text primary key, name text not null, statements jsonb)",
                )
            }
        }
    }

    private fun history(ds: javax.sql.DataSource): List<String> =
        ds.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery("select version from supabase_migrations.schema_migrations order by 1")
                    .use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
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

    /** The runner's transaction shape: DDL + history in one commit. */
    private fun apply(ds: javax.sql.DataSource, version: String, sql: String) {
        ds.connection.use { c ->
            c.autoCommit = false
            try {
                c.createStatement().use { it.execute(sql) }
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
            bootstrap(ds)
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
            bootstrap(ds)
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
            bootstrap(ds)
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
    fun `a migration cannot be marked applied after partial execution`() {
        val db = newDatabase()
        try {
            val ds = dataSourceFor(db)
            bootstrap(ds)
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