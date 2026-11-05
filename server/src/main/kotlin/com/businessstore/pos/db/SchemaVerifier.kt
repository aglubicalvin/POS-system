package com.businessstore.pos.db

import javax.sql.DataSource

/**
 * Read-only verification that a database has been explicitly provisioned.
 *
 * This is the check normal application startup performs. It issues SELECTs only:
 * no schema, no table, no DDL, no migration-history write. Whether the database
 * is ready is therefore decided, never created, by starting the server.
 *
 * A fresh database fails [requireProvisioned] with [SchemaNotProvisionedException],
 * whose message names the exact command that provisions it. The server has no
 * automatic repair path: an unprovisioned database is a stop condition, not a
 * problem to paper over at startup.
 */
object SchemaVerifier {

    /** The required baseline row in supabase_migrations.schema_migrations. */
    const val REQUIRED_VERSION: String = Schema.BASELINE_VERSION

    data class Result(
        val migrationsSchemaPresent: Boolean,
        val migrationsTablePresent: Boolean,
        val baselineRecorded: Boolean,
        val statementsChecked: Int,
    ) {
        val provisioned: Boolean
            get() = migrationsSchemaPresent && migrationsTablePresent && baselineRecorded

        /** A field-by-field verdict, safe to log: booleans and a count only. */
        fun describe(): String =
            "migrations_schema=$migrationsSchemaPresent migrations_table=$migrationsTablePresent " +
                "baseline_$REQUIRED_VERSION=$baselineRecorded"
    }

    /**
     * Inspect the database without modifying it. Every statement here is a SELECT
     * against catalogs or the migration table; none can write.
     */
    fun inspect(dataSource: DataSource): Result {
        var statements = 0
        dataSource.connection.use { conn ->
            val previousAutoCommit = conn.autoCommit
            val previousReadOnly = conn.isReadOnly
            // Ask the driver for a read-only session too. This is defense in
            // depth, not the guarantee: the guarantee is that only SELECTs are
            // issued in this function.
            conn.autoCommit = false
            runCatching { conn.isReadOnly = true }
            try {
                statements++
                val schemaPresent = conn.createStatement().use { statement ->
                    statement.executeQuery(
                        "select 1 from information_schema.schemata where schema_name = 'supabase_migrations'"
                    ).use { rs -> rs.next() }
                }

                var tablePresent = false
                var baseline = false
                if (schemaPresent) {
                    statements++
                    tablePresent = conn.createStatement().use { statement ->
                        statement.executeQuery(
                            "select 1 from information_schema.tables " +
                                "where table_schema = 'supabase_migrations' and table_name = 'schema_migrations'"
                        ).use { rs -> rs.next() }
                    }
                }
                if (tablePresent) {
                    statements++
                    baseline = conn.prepareStatement(
                            "select 1 from supabase_migrations.schema_migrations where version = ?"
                        ).use { ps ->
                        ps.setString(1, REQUIRED_VERSION)
                        ps.executeQuery().use { rs -> rs.next() }
                    }
                }

                runCatching { conn.rollback() }
                return Result(
                    migrationsSchemaPresent = schemaPresent,
                    migrationsTablePresent = tablePresent,
                    baselineRecorded = baseline,
                    statementsChecked = statements,
                )
            } finally {
                runCatching { conn.isReadOnly = previousReadOnly }
                runCatching { conn.autoCommit = previousAutoCommit }
            }
        }
    }

    /**
     * Throw [SchemaNotProvisionedException] unless the baseline is present.
     *
     * The message is deliberately actionable and contains no credential, host or
     * connection string: it names the environment variables and the Gradle task.
     */
    fun requireProvisioned(dataSource: DataSource) {
        val result = inspect(dataSource)
        if (!result.provisioned) {
            throw SchemaNotProvisionedException(
                "Database schema is not provisioned (${result.describe()}). " +
                    "Normal server startup never creates or migrates schema. " +
                    "Provision it explicitly first: set POS_DATABASE_URL (and the matching " +
                    "POS_DATABASE_USER / POS_DATABASE_PASS) plus PROVISION_CONFIRM=yes, then run " +
                    "'./gradlew provisionDatabase' from server/. " +
                    "Migrations 0001-0009 are applied separately, never during baseline provisioning.",
            )
        }
    }
}

/** Raised when the server is started against a database that was never provisioned. */
class SchemaNotProvisionedException(message: String) : IllegalStateException(message)