package com.businessstore.pos

import com.businessstore.pos.api.posModule
import com.businessstore.pos.config.ServerConfig
import com.businessstore.pos.domain.ClientSaleItem
import com.businessstore.pos.domain.ClientTotals
import com.businessstore.pos.domain.PosJson
import com.businessstore.pos.domain.RegisterDeviceResponse
import com.businessstore.pos.domain.SyncBatchRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal fun testConfig(enrollmentSecret: String? = null) = ServerConfig(
    jdbcUrl = "unused-in-tests",
    username = "test",
    password = "test",
    port = 0,
    enrollmentSecret = enrollmentSecret,
)

private suspend fun io.ktor.client.HttpClient.enroll(secret: String? = null): RegisterDeviceResponse {
    val response = post("/devices") {
        contentType(ContentType.Application.Json)
        if (secret != null) header("X-Enrollment-Secret", secret)
        setBody("""{"storeId":"store-accra-01","name":"TILL-1","model":"tablet"}""")
    }
    assertEquals(HttpStatusCode.Created, response.status)
    return PosJson.decodeFromString(RegisterDeviceResponse.serializer(), response.bodyAsText())
}

class SyncApiTest {

    @Test
    fun `health needs no authentication`() = testApplication {
        val db = TestDb.fresh()
        application { posModule(db, testConfig()) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("business-store-server"))
    }

    @Test
    fun `sync without a bearer token is rejected`() = testApplication {
        val db = TestDb.fresh()
        application { posModule(db, testConfig()) }
        val response = client.post("/sync") {
            contentType(ContentType.Application.Json)
            setBody("""{"events":[]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `sync with a bogus token is rejected`() = testApplication {
        val db = TestDb.fresh()
        application { posModule(db, testConfig()) }
        val response = client.post("/sync") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer not-a-real-token")
            setBody("""{"events":[]}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `enrollment is refused without the configured secret`() = testApplication {
        val db = TestDb.fresh()
        application { posModule(db, testConfig(enrollmentSecret = "correct-horse")) }
        val response = client.post("/devices") {
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"store-accra-01","name":"TILL-9"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `an enrolled device can sync a sale end to end`() = testApplication {
        val db = TestDb.fresh()
        val seed = seedServer(db)
        application { posModule(db, testConfig(enrollmentSecret = "correct-horse")) }

        val enrolled = client.enroll("correct-horse")
        val event = saleCreatedEvent(
            seed,
            deviceId = enrolled.deviceId,
            items = listOf(
                ClientSaleItem(productId = seed.waterId, sku = "WATER-500", quantity = 2, unitPrice = 150),
            ),
            clientTotals = ClientTotals(subtotal = 300, discountTotal = 0, taxTotal = 0, total = 300),
        )
        val body = PosJson.encodeToString(SyncBatchRequest.serializer(), SyncBatchRequest(listOf(event)))

        val response = client.post("/sync") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${enrolled.token}")
            setBody(body)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("applied"))
        assertEquals(1L, db.read { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM sales").use { st ->
                st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        })
    }

    @Test
    fun `a device cannot submit events claiming to be another device`() = testApplication {
        val db = TestDb.fresh()
        val seed = seedServer(db)
        application { posModule(db, testConfig()) }

        val enrolled = client.enroll()
        // Same store, different device id in the envelope.
        val forged = saleCreatedEvent(
            seed,
            deviceId = seed.otherDeviceId,
            items = listOf(ClientSaleItem(productId = seed.waterId, quantity = 1, unitPrice = 150)),
            clientTotals = ClientTotals(150, 0, 0, 150),
        )
        val body = PosJson.encodeToString(SyncBatchRequest.serializer(), SyncBatchRequest(listOf(forged)))

        val response = client.post("/sync") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${enrolled.token}")
            setBody(body)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        // Nothing was written on the strength of a forged event.
        assertEquals(0L, db.read { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM sales").use { st ->
                st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        })
    }

    @Test
    fun `reports require authentication`() = testApplication {
        val db = TestDb.fresh()
        application { posModule(db, testConfig()) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/reports/stock").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/reports/review-queue").status)
    }
}
