package com.businessstore.pos

import com.businessstore.pos.api.posModule
import com.businessstore.pos.config.ServerConfig
import com.businessstore.pos.db.Database
import com.businessstore.pos.db.SchemaNotProvisionedException
import com.businessstore.pos.db.SchemaVerifier
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlin.system.exitProcess

/**
 * Normal server startup. It reads, it never writes.
 *
 * Starting the server connects to the configured database and VERIFIES that the
 * baseline schema (`supabase_migrations.schema_migrations`, version 0000) is
 * already present. It does not create a schema, create a table, apply DDL,
 * insert migration history or run migrations: the only code that writes schema
 * is the explicit provisioner (`./gradlew provisionDatabase`).
 *
 * An unprovisioned database is therefore a startup failure with an actionable
 * message, not something startup silently repairs.
 */
fun main() {
    val config = ServerConfig.fromEnv()
    val db = Database.connect(config.jdbcUrl, config.username, config.password)

    try {
        SchemaVerifier.requireProvisioned(db.source())
    } catch (notProvisioned: SchemaNotProvisionedException) {
        // Sanitized: the message names variables and the Gradle task, never a
        // credential, host or connection string.
        System.err.println("STARTUP_FAILED: " + notProvisioned.message)
        exitProcess(1)
    }

    if (config.enrollmentSecret == null) {
        println("WARNING: POS_ENROLLMENT_SECRET is not set — device registration is unauthenticated.")
    }
    println("Business Store POS server listening on :${config.port}")

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        posModule(db, config)
    }.start(wait = true)
}