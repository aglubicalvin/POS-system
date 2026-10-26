package com.businessstore.pos

import com.businessstore.pos.api.posModule
import com.businessstore.pos.config.ServerConfig
import com.businessstore.pos.domain.ClientSaleItem
import com.businessstore.pos.domain.ClientTotals
import com.businessstore.pos.domain.PosJson
import com.businessstore.pos.domain.RegisterDeviceResponse
import com.businessstore.pos.domain.SyncBatchRequest
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The end-to-end check: a REAL Netty server on a real port, driven over a real
 * HTTP connection by the JDK client, against real PostgreSQL.
 *
 * The Ktor test host (`SyncApiTest`) exercises routing, serialisation, auth and
 * StatusPages without binding a socket. This test additionally proves the server
 * actually starts, listens, and serves — enrol a device, then sync a sale with a
 * bearer token, exactly as the TypeScript till will.
 */
class HttpServerIntegrationTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `enrols a device and syncs a sale over a real socket`() {
        val db = TestDb.fresh()
        val seed = seedServer(db)
        val port = freePort()
        val config = ServerConfig(
            jdbcUrl = "unused-in-tests",
            username = "test",
            password = "test",
            port = port,
            enrollmentSecret = null,
        )

        val server = embeddedServer(Netty, port = port, host = "127.0.0.1") { posModule(db, config) }
        server.start(wait = false)

        try {
            val http = HttpClient.newHttpClient()
            val base = "http://127.0.0.1:$port"

            val health = http.send(
                HttpRequest.newBuilder(URI("$base/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, health.statusCode())

            val enroll = http.send(
                HttpRequest.newBuilder(URI("$base/devices"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"storeId":"store-accra-01","name":"TILL-1"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(201, enroll.statusCode())
            val device = PosJson.decodeFromString(RegisterDeviceResponse.serializer(), enroll.body())
            assertTrue(device.token.isNotBlank())

            val event = saleCreatedEvent(
                seed,
                deviceId = device.deviceId,
                items = listOf(
                    ClientSaleItem(productId = seed.waterId, sku = "WATER-500", quantity = 2, unitPrice = 150),
                ),
                clientTotals = ClientTotals(subtotal = 300, discountTotal = 0, taxTotal = 0, total = 300),
            )
            val batch = PosJson.encodeToString(SyncBatchRequest.serializer(), SyncBatchRequest(listOf(event)))

            // Without a token the same request must be refused.
            val unauthorised = http.send(
                HttpRequest.newBuilder(URI("$base/sync"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(batch))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(401, unauthorised.statusCode())

            val sync = http.send(
                HttpRequest.newBuilder(URI("$base/sync"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer ${device.token}")
                    .POST(HttpRequest.BodyPublishers.ofString(batch))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, sync.statusCode())
            assertTrue(sync.body().contains("applied"), "body: ${sync.body()}")

            val stock = http.send(
                HttpRequest.newBuilder(URI("$base/reports/stock"))
                    .header("Authorization", "Bearer ${device.token}")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, stock.statusCode())
            // Two units sold from an empty shelf, so on-hand is -2.
            assertTrue(stock.body().contains("\"onHand\":-2"), "body: ${stock.body()}")
        } finally {
            server.stop(0, 1000, TimeUnit.MILLISECONDS)
        }
    }
}
