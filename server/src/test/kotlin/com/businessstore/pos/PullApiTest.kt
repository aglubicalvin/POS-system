package com.businessstore.pos

import com.businessstore.pos.api.posModule
import com.businessstore.pos.config.ServerConfig
import com.businessstore.pos.domain.PosJson
import com.businessstore.pos.domain.PullResponse
import com.businessstore.pos.domain.RegisterDeviceResponse
import com.businessstore.pos.domain.SyncBatchRequest
import com.businessstore.pos.domain.SyncEventEnvelope
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLEncoder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
data class PullContractFile(val note: String, val events: List<SyncEventEnvelope>)

/** The pull contract fixture, located the same way WireContractTest finds events.json. */
private fun pullFixture(): List<SyncEventEnvelope> {
    val candidates = listOf(
        File("../contract/pull.json"),
        File("contract/pull.json"),
        File(System.getProperty("user.dir")).resolve("../contract/pull.json"),
    )
    val file = candidates.firstOrNull { it.exists() }
        ?: error(
            "contract/pull.json not found. Looked in: ${candidates.joinToString { it.absolutePath }}",
        )
    return PosJson.decodeFromString(PullContractFile.serializer(), file.readText()).events
}

private suspend fun io.ktor.client.HttpClient.enrollAs(name: String): RegisterDeviceResponse {
    val response = post("/devices") {
        contentType(ContentType.Application.Json)
        setBody("""{"storeId":"store-accra-01","name":"$name","model":"tablet"}""")
    }
    assertEquals(HttpStatusCode.Created, response.status)
    return PosJson.decodeFromString(RegisterDeviceResponse.serializer(), response.bodyAsText())
}

/**
 * `GET /sync/pull` — the server → device half of sync.
 *
 * The Kotlin side's obligation to the pull contract: envelopes are relayed
 * VERBATIM to the store's other tills, scoped by store, excluding the caller's
 * own events and the non-relayable event types, and honouring per-source cursors.
 */
class PullApiTest {

    /**
     * The relay is field-complete, not byte-equal: PostgreSQL's JSONB normalises
     * payload key order, and `createdAt` describes the INGEST, which the pull
     * does not carry (the receiving till stamps nothing; it keys on seq only).
     * Compare everything the receiving dispatcher consumes, structurally.
     */
    private fun assertEnvelopeRelays(expected: SyncEventEnvelope, actual: SyncEventEnvelope) {
        assertEquals(expected.eventId, actual.eventId)
        assertEquals(expected.deviceId, actual.deviceId)
        assertEquals(expected.seq, actual.seq)
        assertEquals(expected.eventType, actual.eventType)
        assertEquals(expected.aggregateType, actual.aggregateType)
        assertEquals(expected.aggregateId, actual.aggregateId)
        assertEquals(expected.payload.toMap(), actual.payload.toMap())
    }

    @Test
    fun `relays catalog and user upserts from another till verbatim`() = testApplication {
        val db = TestDb.fresh()
        application { posModule(db, testConfig()) }

        val till1 = client.enrollAs("TILL-1")
        val till2 = client.enrollAs("TILL-2")

        // TILL-1 uploads the fixture's envelopes as its own events.
        val fixture = pullFixture()
        val batch = fixture.mapIndexed { index, event ->
            event.copy(deviceId = till1.deviceId, seq = (index + 1).toLong())
        }
        val upload = client.post("/sync") {
            header(HttpHeaders.Authorization, "Bearer ${till1.token}")
            contentType(ContentType.Application.Json)
            setBody(PosJson.encodeToString(SyncBatchRequest.serializer(), SyncBatchRequest(batch)))
        }
        assertEquals(HttpStatusCode.OK, upload.status)

        // TILL-2 pulls: it must receive the envelopes back, payloads untouched.
        val pulled = client.get("/sync/pull") {
            header(HttpHeaders.Authorization, "Bearer ${till2.token}")
        }
        assertEquals(HttpStatusCode.OK, pulled.status)
        val response = PosJson.decodeFromString(PullResponse.serializer(), pulled.bodyAsText())
        assertEquals(batch.size, response.events.size)
        response.events.forEachIndexed { index, relayed ->
            assertEnvelopeRelays(batch[index], relayed)
        }

        // The fixture's product payload is a full row whether or not a verifier calls it one.
        val first = response.events.first()
        assertEquals("product.upserted", first.eventType)
        assertEquals(
            "FIXTURE-SOAP",
            first.payload.getValue("sku").jsonPrimitive.content,
        )

        // Delivered seqs become the cursor: a pull since them is empty.
        val cursor = URLEncoder.encode(
            """{"${till1.deviceId}":${batch.last().seq}}""",
            Charsets.UTF_8,
        )
        val again = client.get("/sync/pull?since=$cursor") {
            header(HttpHeaders.Authorization, "Bearer ${till2.token}")
        }
        assertTrue(
            PosJson.decodeFromString(PullResponse.serializer(), again.bodyAsText()).events.isEmpty(),
        )

        // A till never receives its own events back.
        val self = client.get("/sync/pull") {
            header(HttpHeaders.Authorization, "Bearer ${till1.token}")
        }
        assertTrue(
            PosJson.decodeFromString(PullResponse.serializer(), self.bodyAsText()).events.isEmpty(),
        )
    }

    @Test
    fun `sale events are not relayed between tills`() = testApplication {
        val db = TestDb.fresh()
        val seed = seedServer(db)
        application { posModule(db, testConfig()) }

        val till1 = client.enrollAs("TILL-1")
        val till2 = client.enrollAs("TILL-2")

        val sale = saleCreatedEvent(
            seed,
            deviceId = till1.deviceId,
            items = listOf(
                com.businessstore.pos.domain.ClientSaleItem(
                    productId = seed.waterId,
                    sku = "WATER-500",
                    quantity = 1,
                    unitPrice = 150,
                ),
            ),
            clientTotals = com.businessstore.pos.domain.ClientTotals(150, 0, 0, 150),
        )
        val upload = client.post("/sync") {
            header(HttpHeaders.Authorization, "Bearer ${till1.token}")
            contentType(ContentType.Application.Json)
            setBody(PosJson.encodeToString(SyncBatchRequest.serializer(), SyncBatchRequest(listOf(sale))))
        }
        assertEquals(HttpStatusCode.OK, upload.status)

        val pulled = client.get("/sync/pull") {
            header(HttpHeaders.Authorization, "Bearer ${till2.token}")
        }
        // Sales reconcile at the server; tills never replicate each other's sales.
        assertTrue(
            PosJson.decodeFromString(PullResponse.serializer(), pulled.bodyAsText()).events.isEmpty(),
        )
    }

    @Test
    fun `pull requires authentication and a well-formed since parameter`() = testApplication {
        val db = TestDb.fresh()
        application { posModule(db, testConfig()) }
        val enrolled = client.enrollAs("TILL-1")

        assertEquals(HttpStatusCode.Unauthorized, client.get("/sync/pull").status)

        val badSince = client.get("/sync/pull?since=not-json") {
            header(HttpHeaders.Authorization, "Bearer ${enrolled.token}")
        }
        assertEquals(HttpStatusCode.BadRequest, badSince.status)

        val badLimit = client.get("/sync/pull?limit=zero") {
            header(HttpHeaders.Authorization, "Bearer ${enrolled.token}")
        }
        assertEquals(HttpStatusCode.BadRequest, badLimit.status)
    }
}
