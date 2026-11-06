package com.businessstore.pos

import com.businessstore.pos.config.ServerConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Server configuration must fail closed.
 *
 * The previous implementation silently defaulted to
 * `jdbc:postgresql://localhost:5432/business_store` when POS_DATABASE_URL was
 * unset, so a hosted deployment with a missing variable would start against a
 * local database. These tests pin the replacement behaviour: no default target,
 * and local development only when explicitly requested.
 */
class ServerConfigTest {

    private val hosted = mapOf(
        ServerConfig.DATABASE_URL_ENV to "jdbc:postgresql://db.example.test:5432/postgres",
        ServerConfig.DATABASE_USER_ENV to "app_user",
        ServerConfig.DATABASE_PASSWORD_ENV to "not-a-real-password",
    )

    @Test
    fun `a missing database url is rejected rather than defaulting to localhost`() {
        val error = assertThrows<IllegalStateException> {
            ServerConfig.fromEnv(mapOf(ServerConfig.DATABASE_USER_ENV to "app_user"))
        }
        assertTrue(error.message!!.contains("CONFIG_INVALID"), error.message)
        assertTrue(error.message!!.contains(ServerConfig.DATABASE_URL_ENV), "message must name the variable")
        assertTrue(
            error.message!!.contains(ServerConfig.LOCAL_DEV_ENV),
            "message must explain the local-development escape hatch",
        )
    }

    @Test
    fun `an invalid database url is rejected`() {
        for (bad in listOf(
            "postgresql://db.example.test/postgres",
            "jdbc:postgresql:///postgres",
            "jdbc:postgresql://db.example.test",
        )) {
            val error = assertThrows<IllegalStateException> {
                ServerConfig.fromEnv(hosted + (ServerConfig.DATABASE_URL_ENV to bad))
            }
            assertTrue(error.message!!.contains("CONFIG_INVALID"), "url '$bad': ${error.message}")
        }
    }

    @Test
    fun `explicit local development configuration is accepted`() {
        val config = ServerConfig.fromEnv(
            mapOf(ServerConfig.LOCAL_DEV_ENV to "yes"),
        )
        assertEquals(ServerConfig.LOCAL_JDBC_URL, config.jdbcUrl)
        assertEquals(ServerConfig.LOCAL_USER, config.username)
        assertEquals(8080, config.port)
        assertNull(config.enrollmentSecret)
    }

    @Test
    fun `local development is refused when the process looks hosted`() {
        // A deployed process must never quietly use a localhost database, even if
        // POS_LOCAL_DEV was left set in the environment.
        for (marker in listOf("FLY_APP_NAME", "RAILWAY_ENVIRONMENT", "RENDER", "DYNO")) {
            val error = assertThrows<IllegalStateException> {
                ServerConfig.fromEnv(
                    mapOf(ServerConfig.LOCAL_DEV_ENV to "yes", marker to "some-app"),
                )
            }
            assertTrue(error.message!!.contains("looks hosted"), "$marker: ${error.message}")
        }
    }

    @Test
    fun `hosted configuration with a missing username is rejected`() {
        val error = assertThrows<IllegalStateException> {
            ServerConfig.fromEnv(hosted - ServerConfig.DATABASE_USER_ENV)
        }
        assertTrue(error.message!!.contains(ServerConfig.DATABASE_USER_ENV), error.message)
    }

    @Test
    fun `a valid hosted configuration is accepted`() {
        val config = ServerConfig.fromEnv(hosted)
        assertEquals("jdbc:postgresql://db.example.test:5432/postgres", config.jdbcUrl)
        assertEquals("app_user", config.username)
        assertEquals("not-a-real-password", config.password)
        assertEquals(8080, config.port)
    }

    @Test
    fun `an explicit url wins over local development defaults`() {
        val config = ServerConfig.fromEnv(
            mapOf(
                ServerConfig.LOCAL_DEV_ENV to "yes",
                ServerConfig.DATABASE_URL_ENV to "jdbc:postgresql://localhost:5433/other_db",
                ServerConfig.DATABASE_USER_ENV to "other_user",
            ),
        )
        assertEquals("jdbc:postgresql://localhost:5433/other_db", config.jdbcUrl)
        assertEquals("other_user", config.username)
    }

    @Test
    fun `optional secrets are null when unset and never defaulted`() {
        val config = ServerConfig.fromEnv(hosted)
        assertNull(config.enrollmentSecret)
        assertNull(config.momoWebhookSecret)
        assertNull(config.momoProvider)
    }
}