package com.businessstore.pos

import com.businessstore.pos.api.posModule
import com.businessstore.pos.config.ServerConfig
import com.businessstore.pos.db.Database
import com.businessstore.pos.db.Schema
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val config = ServerConfig.fromEnv()
    val db = Database.connect(config.jdbcUrl, config.username, config.password)
    Schema.apply(db)

    if (config.enrollmentSecret == null) {
        println("WARNING: POS_ENROLLMENT_SECRET is not set — device registration is unauthenticated.")
    }
    println("Business Store POS server listening on :${config.port}")

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        posModule(db, config)
    }.start(wait = true)
}
