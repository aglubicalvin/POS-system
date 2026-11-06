package com.businessstore.pos

import com.businessstore.pos.tools.MigrationHistory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Transaction and history-table guarantees of the migration runner.
 *
 * These tests call the production operation, `MigrationHistory.apply`, against a
 * disposable embedded database instead of re-implementing its statement
 * sequence, so what is asserted is what the runner actually executes.
 *
 * The history table belongs to the Supabase CLI
 * (`supabase_migrations.schema_migrations`, with `statements text[]` and
 * `name text`), so the cases below cover: a database with no history at all, an
 * existing CLI-shaped table, a CLI table that predates the optional columns, and
 * the `statements jsonb` table written by the earlier ApplyMigration. In every
 * case the bootstrap DDL, the migration and the history row must commit together
 * or not at all.
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

    /** The real production operation, driven exactly as ApplyMigration drives it. */
    private fun apply(ds: javax.sql.DataSource, version: String, sql: String) {
        ds.connection.use { c ->
            MigrationHistory.apply(c, version, "${version}_test.sql", sql)
        }
    }

    /** Exactly what `supabase migration` / `supabase db push` creates. */
    private fun createCliTable(ds: javax.sql.DataSource) {
        ds.connection.use { c ->
            c.createStatement().use { st ->
                st.execute("create schema supabase_migrations")
                st.execute(
                    "create table supabase_migrations.schema_migrations " +
                        "(version text not null primary key)",
                )
                st.execute(
                    "alter table supabase_migrations.schema_migrations " +
                        "add column if not exists statements text[]",
                )
                st.execute(
                    "alter table supabase_migrations.schema_migrations " +
                        "add column if not exists name text",
                )
            }
        }
    }

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

    /** "ARRAY" for the CLI's text[], "jsonb" for the earlier table, null if absent. */
    private fun statementsType(ds: javax.sql.DataSource): String? =
        ds.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    "select data_type from information_schema.columns " +
                        "where table_schema='supabase_migrations' and table_name='schema_migrations' " +
                        "and column_name='statements'",
                ).use { if (it.next()) it.getString(1) else null }
            }
        }

    /** `statements` rendered as text; works for both text[] and jsonb. */
    private fun statementsAsText(ds: javax.sql.DataSource, version: String): String? =
        ds.connection.use { c ->
            c.prepareStatement(
                "select statements::text from supabase_migrations.schema_migrations where version = ?",
            ).use { ps ->
                ps.setString(1, version)
                ps.executeQuery().use { if (it.next()) it.getString(1) else null }
            }
        }

    private fun historyRows(ds: javax.sql.DataSource): List<Pair<String, String?>> {
        if (!trackingTableExists(ds)) return emptyList()
        return ds.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    "select version, name from supabase_migrations.schema_migrations order by 1",
                ).use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
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
            assertEquals(
                "ARRAY",
                statementsType(ds),
                "a fresh table must use statements text[], the CLI shape",
            )
            assertTrue(tableExists(ds, "mig_boot"), "the migration itself must be applied")
            assertEquals(listOf("0005" to "0005_test.sql"), historyRows(ds), "version and name must be recorded")
            assertTrue(
                statementsAsText(ds, "0005")!!.contains(MigrationHistory.PROVENANCE),
                "the row must record how it was written",
            )
        } finally {
            drop(db)
        }
    }

    @Test
    fun `a supabase cli history table is reused, not replaced`() {
        val db = newDatabase()
        try {
            val ds = dataSourceFor(db)
            createCliTable(ds)
            // A row the CLI recorded earlier must survive untouched.
            ds.connection.use { c ->
                c.createStatement().use {
                    it.execute(
                        "insert into supabase_migrations.schema_migrations (version, name, statements) " +
                            "values ('0001', '0001_admin_foundation.sql', array['create table cli_earlier (id int)'])",
                    )
                }
            }

            apply(ds, "0006", "create table mig_cli (id int)")

            assertEquals("ARRAY", statementsType(ds), "the CLI column type must be left alone")
            assertEquals(
                listOf("0001" to "0001_admin_foundation.sql", "0006" to "0006_test.sql"),
                historyRows(ds),
            )
            assertTrue(tableExists(ds, "mig_cli"))
            val recorded = statementsAsText(ds, "0006")!!
            assertTrue(recorded.startsWith("{"), "statements must still be an array, was: $recorded")
            assertTrue(recorded.contains(MigrationHistory.PROVENANCE))
        } finally {
            drop(db)
        }
    }

    @Test
    fun `a cli history table without the optional columns is completed`() {
        val db = newDatabase()
        try {
            val ds = dataSourceFor(db)
            // The first table the CLI ever created had only the version column.
            ds.connection.use { c ->
                c.createStatement().use {
                    it.execute("create schema supabase_migrations")
                    it.execute(
                        "create table supabase_migrations.schema_migrations " +
                            "(version text not null primary key)",
                    )
                }
            }

            apply(ds, "0007", "create table mig_old (id int)")

            assertEquals("ARRAY", statementsType(ds), "the bootstrap must add statements text[]")
            assertEquals(listOf("0007" to "0007_test.sql"), historyRows(ds))
            assertTrue(tableExists(ds, "mig_old"))
        } finally {
            drop(db)
        }
    }

    @Test
    fun `a jsonb history table from the earlier runner still accepts records`() {
        val db = newDatabase()
        try {
            val ds = dataSourceFor(db)
            ds.connection.use { c ->
                c.createStatement().use {
                    it.execute("create schema supabase_migrations")
                    it.execute(
                        "create table supabase_migrations.schema_migrations (" +
                            " version text primary key," +
                            " statements jsonb," +
                            " name text not null," +
                            " statements_hash text," +
                            " executed_at timestamp not null default clock_timestamp())",
                    )
                }
            }

            apply(ds, "0008", "create table mig_json (id int)")

            assertEquals("jsonb", statementsType(ds), "an existing column type must not be altered")
            assertEquals(listOf("0008" to "0008_test.sql"), historyRows(ds))
            assertTrue(statementsAsText(ds, "0008")!!.contains(MigrationHistory.PROVENANCE))
            assertTrue(tableExists(ds, "mig_json"))
        } finally {
            drop(db)
        }
    }

    @Test
    fun `a successful migration records its history`() {
        val db = newDatabase()
        try {
            val ds = dataSourceFor(db)
            apply(ds, "0001", "create table mig_ok (id int primary key)")
            assertTrue(tableExists(ds, "mig_ok"))
            assertEquals(listOf("0001" to "0001_test.sql"), historyRows(ds))
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
            assertFalse(
                trackingTableExists(ds),
                "the bootstrap must roll back with the failed migration",
            )
            assertTrue(historyRows(ds).isEmpty(), "no history row may be recorded")
        } finally {
            drop(db)
        }
    }

    @Test
    fun `a failed history insert rolls back the migration itself`() {
        val db = newDatabase()
        try {
            val ds = dataSourceFor(db)
            createCliTable(ds)
            // A pre-existing table that the runner cannot satisfy: the insert
            // omits this NOT NULL column, so bookkeeping fails after the DDL ran.
            ds.connection.use { c ->
                c.createStatement().use {
                    it.execute(
                        "alter table supabase_migrations.schema_migrations " +
                            "add column cli_only text not null",
                    )
                }
            }
            val failure = runCatching {
                apply(ds, "0003", "create table mig_dup2 (id int)")
            }
            assertTrue(failure.isFailure, "the history insert must fail")
            assertFalse(
                tableExists(ds, "mig_dup2"),
                "the DDL must roll back when the history insert fails",
            )
            assertTrue(historyRows(ds).isEmpty(), "history must be unchanged")
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
            assertTrue(historyRows(ds).isEmpty(), "a partially applied migration must not be recorded")
        } finally {
            drop(db)
        }
    }

    @Test
    fun `bootstrapping is idempotent across successive migrations`() {
        val db = newDatabase()
        try {
            val ds = dataSourceFor(db)
            apply(ds, "0011", "create table mig_seq_a (id int)")
            apply(ds, "0012", "create table mig_seq_b (id int)")
            assertTrue(tableExists(ds, "mig_seq_a"))
            assertTrue(tableExists(ds, "mig_seq_b"), "a second migration must not collide with the bootstrap")
            assertEquals(
                listOf("0011" to "0011_test.sql", "0012" to "0012_test.sql"),
                historyRows(ds),
            )
        } finally {
            drop(db)
        }
    }
}
