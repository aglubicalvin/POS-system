package com.businessstore.pos

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the toolchain and the single biggest platform assumption before any
 * server code depends on it: that we can run REAL PostgreSQL locally without
 * Docker, and speak to it over JDBC.
 */
class ToolchainSmokeTest {

    @Test
    fun `embedded postgres starts and serves real postgres over jdbc`() {
        EmbeddedPostgres.builder().start().use { pg ->
            pg.postgresDatabase.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.execute("CREATE TABLE t (id TEXT PRIMARY KEY, n BIGINT NOT NULL)")
                    st.execute("INSERT INTO t VALUES ('a', 5)")
                }
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT n FROM t").use { rs ->
                        assertTrue(rs.next())
                        assertEquals(5L, rs.getLong(1))
                    }
                }
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT version()").use { rs ->
                        assertTrue(rs.next())
                        val version = rs.getString(1)
                        println("Embedded PostgreSQL: $version")
                        assertTrue(version.contains("PostgreSQL"), "expected a real PostgreSQL server")
                    }
                }
            }
        }
    }
}
