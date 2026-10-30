package com.businessstore.pos

import com.businessstore.pos.api.posModule
import com.businessstore.pos.config.ServerConfig
import com.businessstore.pos.db.Database
import com.businessstore.pos.domain.PosJson
import com.businessstore.pos.domain.RegisterDeviceResponse
import com.businessstore.pos.payments.MobileMoneyInitiateRequest
import com.businessstore.pos.payments.MobileMoneyInitiateResponse
import com.businessstore.pos.payments.MobileMoneyStatusResponse
import com.businessstore.pos.payments.MobileMoneyWebhookRequest
import com.businessstore.pos.payments.MobileMoneyWebhookResponse
import io.ktor.client.HttpClient
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
import java.time.OffsetDateTime
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mobile-money boundary over HTTP against real PostgreSQL.
 *
 * No vendor is contacted: the tests drive the same contract a provider adapter (or
 * the provider itself, for the webhook) would. What is proven is the part that must
 * not be reimplemented per vendor — idempotent initiation, signature enforcement,
 * one confirmation per payment, and refusal of contradictory callbacks.
 */
class MobileMoneyApiTest {

    private val secret = "test-webhook-secret"

    private fun config(webhookSecret: String? = secret) = ServerConfig(
        jdbcUrl = "unused-in-tests",
        username = "test",
        password = "test",
        port = 0,
        enrollmentSecret = null,
        momoWebhookSecret = webhookSecret,
        momoProvider = "mtn-momo",
    )

    private fun Database.insertSale(total: Long): String {
        val saleId = UUID.randomUUID().toString()
        val cashierId = UUID.randomUUID().toString()
        val now = OffsetDateTime.parse("2026-11-01T09:00:00Z")
        transaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO users (user_id, device_id, full_name, role, mfa_enabled,
                                   failed_attempts, is_active, created_at, updated_at)
                VALUES (?, NULL, 'Ama', 'cashier', FALSE, 0, TRUE, ?, ?)
                """.trimIndent(),
            ).use { st ->
                st.setString(1, cashierId)
                st.setObject(2, now)
                st.setObject(3, now)
                st.executeUpdate()
            }
            conn.prepareStatement(
                """
                INSERT INTO sales (sale_id, device_id, receipt_number, cashier_id, currency,
                                   subtotal, discount_total, tax_total, total, kind, status,
                                   sync_state, created_at)
                VALUES (?, 'till-1', ?, ?, 'GHS', ?, 0, 0, ?, 'sale', 'completed', 'synced', ?)
                """.trimIndent(),
            ).use { st ->
                st.setString(1, saleId)
                st.setString(2, "TILL1-" + saleId.take(8))
                st.setString(3, cashierId)
                st.setLong(4, total)
                st.setLong(5, total)
                st.setObject(6, now)
                st.executeUpdate()
            }
        }
        return saleId
    }

    private fun paymentStatus(db: Database, paymentId: String): Pair<String, String?> = db.read { conn ->
        conn.prepareStatement("SELECT status, provider_ref FROM payments WHERE payment_id = ?").use { st ->
            st.setString(1, paymentId)
            st.executeQuery().use { rs ->
                check(rs.next()) { "payment $paymentId missing" }
                rs.getString(1) to rs.getString(2)
            }
        }
    }

    private fun signature(body: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private suspend fun HttpClient.enroll(): RegisterDeviceResponse {
        val response = post("/devices") {
            contentType(ContentType.Application.Json)
            setBody("""{"storeId":"store-accra-01","name":"TILL-1"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return PosJson.decodeFromString(RegisterDeviceResponse.serializer(), response.bodyAsText())
    }

    private suspend fun HttpClient.initiate(
        token: String,
        request: MobileMoneyInitiateRequest,
    ) = post("/payments/mobile-money/initiate") {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.Authorization, "Bearer $token")
        setBody(PosJson.encodeToString(MobileMoneyInitiateRequest.serializer(), request))
    }

    private fun webhookBody(paymentId: String, amount: Long, status: String, ref: String = "MM-1") =
        PosJson.encodeToString(
            MobileMoneyWebhookRequest.serializer(),
            MobileMoneyWebhookRequest(paymentId = paymentId, providerRef = ref, amount = amount, status = status),
        )

    @Test
    fun `initiation is idempotent on the caller's key`() = testApplication {
        val db = TestDb.fresh()
        val saleId = db.insertSale(5000)
        application { posModule(db, config()) }
        val token = client.enroll().token
        val request = MobileMoneyInitiateRequest(
            saleId = saleId,
            amount = 5000,
            payerMsisdn = "0244000000",
            idempotencyKey = "momo-key-0001",
        )

        val first = client.initiate(token, request)
        assertEquals(HttpStatusCode.Created, first.status)
        val created = PosJson.decodeFromString(MobileMoneyInitiateResponse.serializer(), first.bodyAsText())
        assertEquals("pending", created.status)
        assertEquals("mtn-momo", created.provider)
        assertTrue(created.created)

        // The retried tap returns the SAME payment instead of creating a second one.
        val second = client.initiate(token, request)
        assertEquals(HttpStatusCode.OK, second.status)
        val replay = PosJson.decodeFromString(MobileMoneyInitiateResponse.serializer(), second.bodyAsText())
        assertEquals(created.paymentId, replay.paymentId)
        assertTrue(!replay.created)

        val count = db.read { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM payments").use { rs -> rs.next(); rs.getLong(1) }
            }
        }
        assertEquals(1L, count)
    }

    @Test
    fun `initiation rejects an amount above the sale total`() = testApplication {
        val db = TestDb.fresh()
        val saleId = db.insertSale(5000)
        application { posModule(db, config()) }
        val token = client.enroll().token

        val response = client.initiate(
            token,
            MobileMoneyInitiateRequest(saleId, 6000, "0244000000", "momo-key-0002"),
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val count = db.read { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM payments").use { rs -> rs.next(); rs.getLong(1) }
            }
        }
        assertEquals(0L, count)
    }

    @Test
    fun `a signed callback confirms once and a replay is a no-op`() = testApplication {
        val db = TestDb.fresh()
        val saleId = db.insertSale(5000)
        application { posModule(db, config()) }
        val token = client.enroll().token
        val initiated = PosJson.decodeFromString(
            MobileMoneyInitiateResponse.serializer(),
            client.initiate(token, MobileMoneyInitiateRequest(saleId, 5000, "0244000000", "momo-key-0003")).bodyAsText(),
        )

        val body = webhookBody(initiated.paymentId, 5000, "confirmed")

        val unsigned = client.post("/payments/mobile-money/webhook") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Unauthorized, unsigned.status)
        assertEquals("pending" to null, paymentStatus(db, initiated.paymentId))

        val confirmed = client.post("/payments/mobile-money/webhook") {
            contentType(ContentType.Application.Json)
            header("X-Momo-Signature", signature(body, secret))
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, confirmed.status)
        val result = PosJson.decodeFromString(MobileMoneyWebhookResponse.serializer(), confirmed.bodyAsText())
        assertEquals("confirmed", result.status)
        assertTrue(!result.alreadyProcessed)
        assertEquals("confirmed" to "MM-1", paymentStatus(db, initiated.paymentId))

        // The provider re-delivers the callback: same state, no second write.
        val replay = client.post("/payments/mobile-money/webhook") {
            contentType(ContentType.Application.Json)
            header("X-Momo-Signature", signature(body, secret))
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, replay.status)
        val replayed = PosJson.decodeFromString(MobileMoneyWebhookResponse.serializer(), replay.bodyAsText())
        assertTrue(replayed.alreadyProcessed)
        assertEquals("confirmed" to "MM-1", paymentStatus(db, initiated.paymentId))
    }

    @Test
    fun `a forged signature or a wrong amount cannot change the payment`() = testApplication {
        val db = TestDb.fresh()
        val saleId = db.insertSale(5000)
        application { posModule(db, config()) }
        val token = client.enroll().token
        val initiated = PosJson.decodeFromString(
            MobileMoneyInitiateResponse.serializer(),
            client.initiate(token, MobileMoneyInitiateRequest(saleId, 5000, "0244000000", "momo-key-0004")).bodyAsText(),
        )

        val forgedBody = webhookBody(initiated.paymentId, 5000, "confirmed")
        val forged = client.post("/payments/mobile-money/webhook") {
            contentType(ContentType.Application.Json)
            header("X-Momo-Signature", signature(forgedBody, "not-the-secret"))
            setBody(forgedBody)
        }
        assertEquals(HttpStatusCode.Unauthorized, forged.status)

        val wrongAmountBody = webhookBody(initiated.paymentId, 100, "confirmed")
        val wrongAmount = client.post("/payments/mobile-money/webhook") {
            contentType(ContentType.Application.Json)
            header("X-Momo-Signature", signature(wrongAmountBody, secret))
            setBody(wrongAmountBody)
        }
        assertEquals(HttpStatusCode.Conflict, wrongAmount.status)

        assertEquals("pending" to null, paymentStatus(db, initiated.paymentId))
    }

    @Test
    fun `the webhook stays disabled without a configured secret`() = testApplication {
        val db = TestDb.fresh()
        val saleId = db.insertSale(5000)
        application { posModule(db, config(webhookSecret = null)) }
        val token = client.enroll().token
        val initiated = PosJson.decodeFromString(
            MobileMoneyInitiateResponse.serializer(),
            client.initiate(token, MobileMoneyInitiateRequest(saleId, 5000, "0244000000", "momo-key-0005")).bodyAsText(),
        )

        val body = webhookBody(initiated.paymentId, 5000, "confirmed")
        val response = client.post("/payments/mobile-money/webhook") {
            contentType(ContentType.Application.Json)
            header("X-Momo-Signature", signature(body, "anything"))
            setBody(body)
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("pending" to null, paymentStatus(db, initiated.paymentId))
    }

    @Test
    fun `payment status needs a device token`() = testApplication {
        val db = TestDb.fresh()
        val saleId = db.insertSale(5000)
        application { posModule(db, config()) }
        val token = client.enroll().token
        val initiated = PosJson.decodeFromString(
            MobileMoneyInitiateResponse.serializer(),
            client.initiate(token, MobileMoneyInitiateRequest(saleId, 5000, "0244000000", "momo-key-0006")).bodyAsText(),
        )

        val anonymous = client.get("/payments/mobile-money/${initiated.paymentId}")
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)

        val authorised = client.get("/payments/mobile-money/${initiated.paymentId}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, authorised.status)
        val status = PosJson.decodeFromString(MobileMoneyStatusResponse.serializer(), authorised.bodyAsText())
        assertEquals("pending", status.status)
        assertEquals(5000, status.amount)
    }
}
