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

/** The canonical Postgres DDL, loaded from one resource shared with the TS tests. */
object Schema {
    private const val RESOURCE = "/db/schema.sql"

    fun ddl(): String =
        Schema::class.java.getResourceAsStream(RESOURCE)?.bufferedReader()?.use { it.readText() }
            ?: error("schema resource $RESOURCE not found on the classpath")

    fun apply(dataSource: DataSource) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { statement -> statement.execute(ddl()) }
        }
    }

    fun apply(database: Database) {
        apply(database.source())
    }
}
