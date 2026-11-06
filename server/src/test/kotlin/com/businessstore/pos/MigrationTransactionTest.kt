package com.businessstore.pos

import com.businessstore.pos.tools.MigrationHistory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Transaction, history-content and connection-lifecycle guarantees of the
 * migration runner.
 *
 * These tests call the production operation, `MigrationHistory.apply`, against a
 * disposable embedded database instead of re-implementing its statement
 * sequence, so what is asserted is what the runner actually executes.
 *
 * Three things are covered here:
 *
 *  1. The history table is the Supabase CLI's (`statements text[]`, `name text`),
 *     so a database with no history, a CLI-shaped table, a CLI table that
 *     predates the optional columns and the earlier `statements jsonb` table all
 *     accept the row.
 *  2. `statements` holds the migration FILE, not a placeholder, so
 *     `supabase migration fetch` (which writes `statements.join(";\n") + ";\n"`)
 *     reproduces executable SQL that keeps functions, comments, quoted strings
 *     and multi-statement files intact.
 *  3. A failed rollback must never be followed by `setAutoCommit(true)` (JDBC
 *     commits an active transaction when autocommit is enabled): the connection
 *     is aborted instead and the failure is reported as an uncertain outcome.
 *  4. Connection disposal is reported explicitly - ABORTED, CLOSED_FALLBACK or
 *     DISPOSAL_UNCERTAIN - with each cleanup failure preserved as a suppressed
 *     exception, including when the migration itself committed (tests A-D).
 *
 * The `supabase migration fetch` check is a real CLI run against the embedded
 * server. It skips on a developer machine without the CLI but FAILS when
 * `SUPABASE_CLI_REQUIRED=true`, which is how CI runs it.
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
     * The real production operation, driven as ApplyMigration drives it. `name`
     * is recorded the way the CLI records it: without the version prefix and
     * without the `.sql` suffix.
     */
    private fun apply(
        ds: javax.sql.DataSource,
        version: String,
        sql: String,
        name: String = "test",
    ) {
        ds.connection.use { c ->
            MigrationHistory.apply(c, version, name, sql)
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

    /** The `statements` array as the CLI reads it: one string per element. */
    private fun statementsElements(ds: javax.sql.DataSource, version: String): List<String> {
        ds.connection.use { c ->
            c.prepareStatement(
                "select statements from supabase_migrations.schema_migrations where version = ?",
            ).use { ps ->
                ps.setString(1, version)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return emptyList()
                    val array = rs.getArray(1) ?: return emptyList()
                    val values = array.array as Array<*>
                    return values.map { it as String }
                }
            }
        }
    }

    /** First element of a legacy `jsonb` column, read the way a JSON reader would. */
    private fun jsonbElement(ds: javax.sql.DataSource, version: String): String? =
        ds.connection.use { c ->
            c.prepareStatement(
                "select statements->>0 from supabase_migrations.schema_migrations where version = ?",
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

    private fun scalar(ds: javax.sql.DataSource, sql: String): String? =
        ds.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    /**
     * Exactly how `supabase migration fetch` writes a history row back to disk:
     * `statements.join(";\n") + ";\n"`
     * (apps/cli/src/legacy/commands/migration/fetch/fetch.handler.ts).
     */
    private fun reconstruct(statements: List<String>): String =
        statements.joinToString(";\n") + ";\n"

    /**
     * A migration file that a naive semicolon split would corrupt: a leading
     * comment, several statements, a semicolon inside a string literal and a
     * dollar-quoted function body that itself contains a semicolon.
     */
    private val multiStatementMigration = """
        -- split on ';' would break this file
        create table mig_rec (
            id int primary key,
            note text
        );

        insert into mig_rec (id, note) values (1, 'semicolon; inside a literal');

        create function mig_rec_label() returns text
        language sql as ${'$'}${'$'}
        select 'dollar; quoted body'::text
        ${'$'}${'$'};

        comment on table mig_rec is 'kept exactly as written';
    """.trimIndent()

    // ---------------------------------------------------------------- history

    @Test
    fun `a database with no migration history is bootstrapped`() {
        val db = newDatabase()
        try {
            val ds = dataSourceFor(db)
            assertFalse(trackingSchemaExists(ds), "the fixture must start without the tracking schema")
            assertFalse(trackingTableExists(ds), "the fixture must start without the tracking table")

            val sql = "create table mig_boot (id int primary key, owner text)"
            apply(ds, "0005", sql)

            assertTrue(trackingSchemaExists(ds), "the runner must create supabase_migrations")
            assertTrue(trackingTableExists(ds), "the runner must create schema_migrations")
            assertEquals(
                "ARRAY",
                statementsType(ds),
                "a fresh table must use statements text[], the CLI shape",
            )
            assertTrue(tableExists(ds, "mig_boot"), "the migration itself must be applied")
            assertEquals(listOf("0005" to "test"), historyRows(ds), "version and name must be recorded")
            assertEquals(listOf(sql), statementsElements(ds, "0005"))
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
                listOf("0001" to "0001_admin_foundation.sql", "0006" to "test"),
                historyRows(ds),
            )
            assertTrue(tableExists(ds, "mig_cli"))
            assertEquals(listOf("create table mig_cli (id int)"), statementsElements(ds, "0006"))
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
            assertEquals(listOf("0007" to "test"), historyRows(ds))
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

            val sql = "create table mig_json (id int)"
            apply(ds, "0008", sql)

            assertEquals("jsonb", statementsType(ds), "an existing column type must not be altered")
            assertEquals(listOf("0008" to "test"), historyRows(ds))
            assertTrue(tableExists(ds, "mig_json"))
            assertEquals(sql, jsonbElement(ds, "0008"))
        } finally {
            drop(db)
        }
    }

    @Test
    fun `the recorded statements array holds the migration file as one element`() {
        val db = newDatabase()
        try {
            val ds = dataSourceFor(db)
            apply(ds, "0012", multiStatementMigration, name = "rec")

            val elements = statementsElements(ds, "0012")
            assertEquals(1, elements.size, "one element must hold the whole file, got: $elements")
            assertEquals(multiStatementMigration, elements.single())
            assertTrue(
                elements.single().contains("create function mig_rec_label()"),
                "the function body must be recorded verbatim",
            )
        } finally {
            drop(db)
        }
    }

    @Test
    fun `the legacy jsonb column holds the migration file`() {
        val db = newDatabase()
        try {
            val ds = dataSourceFor(db)
            ds.connection.use { c ->
                c.createStatement().use {
                    it.execute("create schema supabase_migrations")
                    it.execute(
                        "create table supabase_migrations.schema_migrations (" +
                            " version text primary key, statements jsonb, name text not null)",
                    )
                }
            }

            apply(ds, "0013", multiStatementMigration, name = "rec")

            assertEquals("jsonb", statementsType(ds), "the legacy column type must not be altered")
            assertEquals(multiStatementMigration, jsonbElement(ds, "0013"))
        } finally {
            drop(db)
        }
    }

    @Test
    fun `the provenance placeholder is never written`() {
        val db = newDatabase()
        try {
            val ds = dataSourceFor(db)
            val sql = "create table mig_no_marker (id int)"
            apply(ds, "0014", sql)

            val recorded = statementsElements(ds, "0014").single()
            assertEquals(sql, recorded)
            assertFalse(
                recorded.contains("applied by ApplyMigration"),
                "the history row must carry the migration, not a placeholder",
            )
        } finally {
            drop(db)
        }
    }

    // ---------------------------------------------------------- reconstruction

    @Test
    fun `fetch reconstruction reproduces the original file and stays executable`() {
        val db = newDatabase()
        val replica = newDatabase()
        try {
            val ds = dataSourceFor(db)
            apply(ds, "0010", multiStatementMigration, name = "rec")

            val reconstructed = reconstruct(statementsElements(ds, "0010"))
            assertEquals(multiStatementMigration + ";\n", reconstructed)
            assertTrue(
                reconstructed.contains("-- split on ';' would break this file"),
                "comments must survive",
            )
            assertTrue(
                reconstructed.contains("semicolon; inside a literal"),
                "quoted semicolons must survive",
            )
            assertTrue(
                reconstructed.contains("dollar; quoted body"),
                "dollar-quoted bodies must survive",
            )

            // The reconstructed text must be executable SQL on its own.
            dataSourceFor(replica).connection.use { c ->
                c.createStatement().use { it.execute(reconstructed) }
            }
            val restored = dataSourceFor(replica)
            assertTrue(tableExists(restored, "mig_rec"), "reconstruction must recreate the table")
            assertEquals("dollar; quoted body", scalar(restored, "select mig_rec_label()"))
            assertEquals(
                "kept exactly as written",
                scalar(restored, "select obj_description('mig_rec'::regclass, 'pg_class')"),
            )
            assertEquals(
                "1",
                scalar(restored, "select count(*)::text from mig_rec"),
                "the recorded insert must be part of the reconstruction",
            )
        } finally {
            drop(db)
            drop(replica)
        }
    }

    /**
     * The real CLI, or null when this machine has none. CI sets
     * SUPABASE_CLI_REQUIRED=true (the workflow installs the pinned CLI), so a
     * missing binary is a FAILURE there and only a skip on a developer machine.
     * A skipped run in CI used to hide a broken fetch contract.
     */
    private fun requiredSupabaseCli(): String? {
        val cli = findSupabaseCli()
        if (cli == null && System.getenv("SUPABASE_CLI_REQUIRED") == "true") {
            throw AssertionError(
                "SUPABASE_CLI_REQUIRED=true but no supabase CLI was found. CI installs it " +
                    "with supabase/setup-cli; set SUPABASE_CLI=<path> to point at a binary.",
            )
        }
        return cli
    }

    @Test
    fun `the supabase cli fetches the recorded migration`() {
        val cli = requiredSupabaseCli()
        assumeTrue(
            cli != null,
            "supabase CLI not installed; set SUPABASE_CLI=<path> to run this integration check",
        )
        val db = newDatabase()
        val workdir = Files.createTempDirectory("mig_fetch_")
        try {
            apply(dataSourceFor(db), "0011", multiStatementMigration, name = "rec")
            val migrations = Files.createDirectories(
                workdir.resolve("supabase").resolve("migrations"),
            )
            // The disposable embedded server speaks plaintext only; the CLI
            // defaults to TLS. Nothing here is remote.
            val url = "postgresql://postgres@127.0.0.1:${TestDb.port}/$db?sslmode=disable"

            // Output goes to a FILE, not a pipe we read before waiting: reading
            // a pipe to EOF blocks until the process exits, so waitFor(timeout)
            // could never fire if the CLI hung while producing a lot of output.
            val log = workdir.resolve("fetch.log").toFile()
            val process = ProcessBuilder(
                cli!!, "migration", "fetch", "--db-url", url, "--workdir", workdir.toString(),
            ).redirectErrorStream(true).redirectOutput(log).start()

            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(30, TimeUnit.SECONDS)
                throw AssertionError(
                    "supabase migration fetch did not finish in 180s; output so far:\n" +
                        log.readText(),
                )
            }
            val output = log.readText()
            assertEquals(0, process.exitValue(), "supabase migration fetch failed: $output")

            // The recorded name and statements must round-trip through the CLI's
            // own file writer, not just through our reconstruction helper.
            val fetched = migrations.resolve("0011_rec.sql")
            assertTrue(Files.exists(fetched), "the CLI must write $fetched; output: $output")
            assertEquals(
                multiStatementMigration + ";\n",
                Files.readString(fetched),
                "the fetched file must be the original migration",
            )
            assertEquals(
                listOf("0011_rec.sql"),
                migrations.toFile().list()!!.sorted(),
                "the CLI must write exactly one migration file; output: $output",
            )
        } finally {
            workdir.toFile().deleteRecursively()
            drop(db)
        }
    }

    // -------------------------------------------------------------- behaviour

    @Test
    fun `a successful migration records its history`() {
        val db = newDatabase()
        try {
            val ds = dataSourceFor(db)
            apply(ds, "0001", "create table mig_ok (id int primary key)")
            assertTrue(tableExists(ds, "mig_ok"))
            assertEquals(listOf("0001" to "test"), historyRows(ds))
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
                listOf("0011" to "test", "0012" to "test"),
                historyRows(ds),
            )
        } finally {
            drop(db)
        }
    }

    // ------------------------------------------------- connection lifecycle

    @Test
    fun `a failed rollback never commits and discards the connection`() {
        val db = newDatabase()
        val real = dataSourceFor(db).connection
        try {
            val faulty = FaultyConnection(real).also { it.failRollback = true }
            val thrown = runCatching {
                MigrationHistory.apply(
                    faulty.proxy,
                    "0090",
                    "rb",
                    "create table mig_rb (id int); select * from mig_rb_missing",
                )
            }.exceptionOrNull()

            // 1. The migration's own error stays primary.
            val failure = thrown as? SQLException
                ?: error("expected the migration to fail, got: $thrown")
            assertEquals("42P01", failure.sqlState, "the migration error must stay primary: $failure")

            // 2. The rollback failure is attached, never swallowed.
            val rollbackFailures = failure.suppressed.filterIsInstance<MigrationHistory.RollbackFailed>()
            assertEquals(1, rollbackFailures.size, "suppressed: ${failure.suppressed.toList()}")
            assertEquals(
                "08006",
                (rollbackFailures.single().cause as SQLException).sqlState,
                "the driver's rollback error must be the cause",
            )
            assertTrue(MigrationHistory.rollbackFailed(failure))
            assertFalse(MigrationHistory.discardFailed(failure), "abort must have succeeded")

            // 3. Nothing ran that could commit the still-open transaction.
            assertTrue(faulty.calls.any { it.startsWith("rollback") }, "calls: ${faulty.calls}")
            assertFalse(
                faulty.calls.any { it == "setAutoCommit[true]" },
                "autocommit must never be enabled after a failed rollback: ${faulty.calls}",
            )
            assertFalse(
                faulty.calls.dropWhile { !it.startsWith("rollback") }.any { it.startsWith("commit") },
                "no commit may follow the failed rollback: ${faulty.calls}",
            )

            // 4. The physical connection was terminated, not merely reset.
            assertTrue(faulty.proxy.isClosed, "the failed connection must be discarded")

            // 5. Verified on an independent connection after termination.
            val check = dataSourceFor(db)
            assertFalse(tableExists(check, "mig_rb"), "the DDL must not survive a discarded transaction")
            assertTrue(historyRows(check).isEmpty(), "no history row may survive")
        } finally {
            runCatching { real.close() }
            drop(db)
        }
    }

    /**
     * Test A: migration fails, rollback fails, abort fails, close succeeds.
     *
     * Every step is observed through the proxy, and the database is inspected on
     * an independent connection afterwards.
     */
    @Test
    fun `a failed rollback with a failed abort and a close fallback is reported closed fallback`() {
        val db = newDatabase()
        val real = dataSourceFor(db).connection
        try {
            val faulty = FaultyConnection(real).also {
                it.failRollback = true
                it.failAbort = true
            }
            val failure = runCatching {
                MigrationHistory.apply(
                    faulty.proxy,
                    "0091",
                    "rb",
                    "create table mig_rb2 (id int); select * from mig_rb2_missing",
                )
            }.exceptionOrNull() as? SQLException ?: error("expected the migration to fail")

            // The migration's own error stays primary; rollback and abort are
            // suppressed, not dropped.
            assertEquals("42P01", failure.sqlState, "the migration error must stay primary")
            assertEquals(
                1,
                failure.suppressed.count { it is MigrationHistory.RollbackFailed },
                "suppressed: ${failure.suppressed.toList()}",
            )
            assertTrue(
                failure.suppressed.any { it is MigrationHistory.DiscardFailed },
                "the abort failure must stay observable: ${failure.suppressed.toList()}",
            )

            // Autocommit is never re-enabled and no commit follows the failure.
            assertFalse(
                faulty.calls.any { it == "setAutoCommit[true]" },
                "autocommit must never be enabled after a failed rollback: ${faulty.calls}",
            )
            assertFalse(
                faulty.calls.dropWhile { !it.startsWith("rollback") }.any { it.startsWith("commit") },
                "no commit may follow the failed rollback: ${faulty.calls}",
            )

            // Abort was attempted, then the close fallback was attempted and won.
            assertTrue(faulty.calls.contains("abort(failed)"), "calls: ${faulty.calls}")
            assertTrue(faulty.calls.any { it.startsWith("close") }, "calls: ${faulty.calls}")
            val record = failure.suppressed
                .filterIsInstance<MigrationHistory.DisposalRecord>().single()
            assertFalse(record.abortSucceeded)
            assertTrue(record.closeAttempted)
            assertTrue(record.closeSucceeded)
            assertEquals(MigrationHistory.Disposal.CLOSED_FALLBACK, record.disposal)
            assertEquals(
                MigrationHistory.Disposal.CLOSED_FALLBACK,
                MigrationHistory.disposalOf(failure),
            )

            // This is what the embedded server did with a connection closed after
            // a failed rollback; it is an observation, not a JDBC guarantee.
            assertTrue(faulty.proxy.isClosed, "the fallback close must have run")
            val check = dataSourceFor(db)
            assertFalse(tableExists(check, "mig_rb2"), "the DDL must not survive")
            assertTrue(historyRows(check).isEmpty(), "no history row may survive")
        } finally {
            runCatching { real.close() }
            drop(db)
        }
    }

    /** Test B: commit succeeds, autocommit reset fails, abort succeeds. */
    @Test
    fun `a committed migration with a failed reset and a successful abort is reported aborted`() {
        val db = newDatabase()
        val real = dataSourceFor(db).connection
        try {
            val faulty = FaultyConnection(real).also { it.failAutoCommitReset = true }
            val thrown = runCatching {
                MigrationHistory.apply(
                    faulty.proxy,
                    "0092",
                    "ok",
                    "create table mig_committed_b (id int)",
                )
            }.exceptionOrNull()

            assertTrue(
                thrown is MigrationHistory.CommittedButUnclean,
                "a durable commit must not be reported as a failed migration: $thrown",
            )
            val unclean = thrown as MigrationHistory.CommittedButUnclean
            assertEquals(MigrationHistory.Disposal.ABORTED, unclean.disposal)
            assertEquals(MigrationHistory.Disposal.ABORTED, MigrationHistory.disposalOf(unclean))
            // abort(Executor) records its argument, so match the prefix.
            assertTrue(faulty.calls.any { it.startsWith("abort[") }, "calls: ${faulty.calls}")
            assertFalse(
                faulty.calls.any { it.startsWith("close") },
                "a successful abort must not fall back to close: ${faulty.calls}",
            )

            // The commit stands; verified independently.
            val check = dataSourceFor(db)
            assertTrue(tableExists(check, "mig_committed_b"))
            assertEquals(listOf("0092" to "ok"), historyRows(check))
        } finally {
            runCatching { real.close() }
            drop(db)
        }
    }

    /** Test C: commit succeeds, reset fails, abort fails, close succeeds. */
    @Test
    fun `a committed migration with failed abort and close fallback is reported closed fallback`() {
        val db = newDatabase()
        val real = dataSourceFor(db).connection
        try {
            val faulty = FaultyConnection(real).also {
                it.failAutoCommitReset = true
                it.failAbort = true
            }
            val unclean = runCatching {
                MigrationHistory.apply(
                    faulty.proxy,
                    "0093",
                    "ok",
                    "create table mig_committed_c (id int)",
                )
            }.exceptionOrNull() as? MigrationHistory.CommittedButUnclean
                ?: error("a committed migration must be reported as applied")

            assertEquals(MigrationHistory.Disposal.CLOSED_FALLBACK, unclean.disposal)
            assertTrue(faulty.calls.contains("abort(failed)"), "calls: ${faulty.calls}")
            assertTrue(faulty.calls.any { it.startsWith("close") }, "calls: ${faulty.calls}")
            assertTrue(
                unclean.suppressed.any { it is MigrationHistory.DiscardFailed },
                "the abort failure must stay observable: ${unclean.suppressed.toList()}",
            )
            assertTrue(faulty.proxy.isClosed, "the close fallback must have run")

            val check = dataSourceFor(db)
            assertTrue(tableExists(check, "mig_committed_c"), "the commit must stand")
            assertEquals(listOf("0093" to "ok"), historyRows(check))
        } finally {
            runCatching { real.close() }
            drop(db)
        }
    }

    /** Test D: commit succeeds, reset fails, abort fails, close fails. */
    @Test
    fun `a committed migration with no working disposal is reported disposal uncertain`() {
        val db = newDatabase()
        val real = dataSourceFor(db).connection
        try {
            val faulty = FaultyConnection(real).also {
                it.failAutoCommitReset = true
                it.failAbort = true
                it.failClose = true
            }
            val unclean = runCatching {
                MigrationHistory.apply(
                    faulty.proxy,
                    "0094",
                    "ok",
                    "create table mig_committed_d (id int)",
                )
            }.exceptionOrNull() as? MigrationHistory.CommittedButUnclean
                ?: error("a committed migration must be reported as applied")

            assertEquals(MigrationHistory.Disposal.DISPOSAL_UNCERTAIN, unclean.disposal)
            assertTrue(faulty.calls.contains("abort(failed)"), "calls: ${faulty.calls}")
            assertTrue(faulty.calls.contains("close(failed)"), "calls: ${faulty.calls}")

            // Both cleanup failures stay attached, so "uncertain" is diagnosable.
            val discardErrors = unclean.suppressed.filterIsInstance<MigrationHistory.DiscardFailed>()
            assertEquals(
                2,
                discardErrors.size,
                "abort and close failures must both be preserved: ${unclean.suppressed.toList()}",
            )
            val record = unclean.suppressed
                .filterIsInstance<MigrationHistory.DisposalRecord>().single()
            assertFalse(record.abortSucceeded)
            assertTrue(record.closeAttempted)
            assertFalse(record.closeSucceeded)

            // The migration is still committed, despite the connection outcome.
            val check = dataSourceFor(db)
            assertTrue(tableExists(check, "mig_committed_d"))
            assertEquals(listOf("0094" to "ok"), historyRows(check))
        } finally {
            runCatching { real.close() }
            drop(db)
        }
    }

    /**
     * Records the calls the runner makes and injects failures into them, so the
     * real production lifecycle can be observed rather than copied.
     */
    private class FaultyConnection(private val real: Connection) {
        val calls = mutableListOf<String>()
        var failRollback = false
        var failAbort = false
        var failClose = false
        var failAutoCommitReset = false

        val proxy: Connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf<Class<*>>(Connection::class.java),
        ) { _, method, args ->
            val values = args ?: emptyArray()
            when {
                method.name == "rollback" && failRollback -> {
                    calls += "rollback(failed)"
                    throw SQLException("injected rollback failure", "08006")
                }
                method.name == "abort" && failAbort -> {
                    calls += "abort(failed)"
                    throw SQLException("injected abort failure", "08003")
                }
                method.name == "close" && failClose -> {
                    calls += "close(failed)"
                    throw SQLException("injected close failure", "08003")
                }
                method.name == "setAutoCommit" && values.firstOrNull() == true &&
                    failAutoCommitReset -> {
                    calls += "setAutoCommit[true](failed)"
                    throw SQLException("injected autocommit failure", "08003")
                }
                else -> {
                    calls += method.name +
                        values.joinToString(",", "[", "]") { it?.toString() ?: "null" }
                    try {
                        method.invoke(real, *values)
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            }
        } as Connection
    }

    /** The real CLI when this machine has one (CI installs it before the tests). */
    private fun findSupabaseCli(): String? =
        listOfNotNull(System.getenv("SUPABASE_CLI"), "supabase", "supabase.cmd", "supabase.exe")
            .firstOrNull { exe ->
                runCatching {
                    val process = ProcessBuilder(exe, "--version")
                        .redirectErrorStream(true)
                        .start()
                    process.inputStream.bufferedReader().readText()
                    process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0
                }.getOrDefault(false)
            }
}
