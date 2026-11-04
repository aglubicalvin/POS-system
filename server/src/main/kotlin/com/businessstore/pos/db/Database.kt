package com.businessstore.pos.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import javax.sql.DataSource

/**
 * JDBC access with explicit transactions.
 *
 * The server uses plain SQL rather than an ORM because the schema is shared with
 * the device dialect and several statements (the idempotency claim, the stock
 * upsert) depend on exact ON CONFLICT semantics.
 */
class Database(private val dataSource: DataSource) {

    /** Run `block` in one transaction; commit on success, roll back on any throw. */
    fun <T> transaction(block: (Connection) -> T): T {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                return result
            } catch (error: Throwable) {
                try {
                    conn.rollback()
                } catch (_: Throwable) {
                    // The connection may already be broken; surface the original error.
                }
                throw error
            } finally {
                conn.autoCommit = true
            }
        }
    }

    fun <T> read(block: (Connection) -> T): T = dataSource.connection.use(block)

    /** Exposed for schema migration and health checks only. */
    fun source(): DataSource = dataSource

    fun close() {
        (dataSource as? HikariDataSource)?.close()
    }

    companion object {
        fun connect(
            jdbcUrl: String,
            username: String,
            password: String,
            maxPoolSize: Int = 10,
        ): Database {
            val config = HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                this.username = username
                this.password = password
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = maxPoolSize
                poolName = "business-store-pos"
            }
            return Database(HikariDataSource(config))
        }

        fun from(dataSource: DataSource): Database = Database(dataSource)
    }
}

/**
 * The canonical Postgres DDL, loaded from one resource shared with the TS tests.
 *
 * `apply` is the ONLY code path in the server that writes schema. It is reached
 * exclusively from the explicit provisioner (`ProvisionKt`), never from normal
 * application startup: `Application.main` calls [SchemaVerifier.requireProvisioned]
 * instead, which only reads. Keeping the two apart is what makes "starting the
 * server cannot change the database" true by construction rather than by
 * convention.
 */
object Schema {
    private const val RESOURCE = "/db/schema.sql"

    /** The baseline version recorded when the canonical DDL is applied. */
    const val BASELINE_VERSION = "0000"
    private const val BASELINE_NAME = "baseline_schema"

    fun ddl(): String =
        Schema::class.java.getResourceAsStream(RESOURCE)?.bufferedReader()?.use { it.readText() }
            ?: error("schema resource $RESOURCE not found on the classpath")

    /**
     * Apply the baseline schema and record version 0000, atomically.
     *
     * Everything runs in ONE transaction with autocommit disabled: if any step
     * fails, the whole thing rolls back and the database is left exactly as it
     * was. The previous implementation used three autocommit statements, so a
     * failure after the first left an empty `supabase_migrations` schema behind
     * that looked like partial progress but was not recorded.
     *
     * Idempotent: re-running against an already-provisioned database commits
     * successfully and records nothing new (`on conflict do nothing`).
     *
     * @return true when this call created the baseline, false when it was
     *         already present. A false result is NOT an error.
     */
    fun apply(dataSource: DataSource): Boolean {
        var created = false
        dataSource.connection.use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                conn.createStatement().use { statement ->
                    statement.execute(
                        "create schema if not exists supabase_migrations"
                    )
                }
                conn.createStatement().use { statement ->
                    statement.execute(
                        "create table if not exists supabase_migrations.schema_migrations (" +
                            " version text primary key," +
                            " statements jsonb," +
                            " name text not null," +
                            " statements_hash text," +
                            " executed_at timestamp not null default clock_timestamp())"
                    )
                }
                conn.createStatement().use { statement ->
                    statement.execute(ddl())
                }
                created = conn.prepareStatement(
                    "insert into supabase_migrations.schema_migrations (version, name, statements) " +
                        "values (?, ?, to_jsonb(?::text)) " +
                        "on conflict (version) do nothing"
                ).use { insert ->
                    insert.setString(1, BASELINE_VERSION)
                    insert.setString(2, BASELINE_NAME)
                    insert.setString(3, "applied by explicit provisioning")
                    insert.executeUpdate() > 0
                }
                conn.commit()
            } catch (error: Throwable) {
                try {
                    conn.rollback()
                } catch (rollbackError: Throwable) {
                    // Preserve the original failure; keep the rollback failure as
                    // suppressed context rather than masking the real cause.
                    error.addSuppressed(rollbackError)
                }
                throw error
            } finally {
                runCatching { conn.autoCommit = previousAutoCommit }
            }
        }
        return created
    }

    fun apply(database: Database): Boolean = apply(database.source())
}
