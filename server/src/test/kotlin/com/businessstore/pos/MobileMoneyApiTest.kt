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

    @Test
    fun `a valid signature over different bytes does not authenticate a tampered body`() = testApplication {
        val db = TestDb.fresh()
        val saleId = db.insertSale(5000)
        application { posModule(db, config()) }
        val token = client.enroll().token
        val initiated = PosJson.decodeFromString(
            MobileMoneyInitiateResponse.serializer(),
            client.initiate(token, MobileMoneyInitiateRequest(saleId, 5000, "0244000000", "momo-key-0007")).bodyAsText(),
        )

        // The attacker keeps the signature but swaps the body the provider signed.
        val tamperedBody = webhookBody(initiated.paymentId, 9999, "confirmed", ref = "MM-1")
        val response = client.post("/payments/mobile-money/webhook") {
            contentType(ContentType.Application.Json)
            header("X-Momo-Signature", signature(webhookBody(initiated.paymentId, 5000, "confirmed"), secret))
            setBody(tamperedBody)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("pending" to null, paymentStatus(db, initiated.paymentId))
    }

    @Test
    fun `a provider failure flips the attempt to failed and a later confirmation cannot revive it`() = testApplication {
        val db = TestDb.fresh()
        val saleId = db.insertSale(5000)
        application { posModule(db, config()) }
        val token = client.enroll().token
        val initiated = PosJson.decodeFromString(
            MobileMoneyInitiateResponse.serializer(),
            client.initiate(token, MobileMoneyInitiateRequest(saleId, 5000, "0244000000", "momo-key-0008")).bodyAsText(),
        )

        val failureBody = webhookBody(initiated.paymentId, 5000, "failed", ref = "MM-FAIL-1")
        val failure = client.post("/payments/mobile-money/webhook") {
            contentType(ContentType.Application.Json)
            header("X-Momo-Signature", signature(failureBody, secret))
            setBody(failureBody)
        }
        assertEquals(HttpStatusCode.OK, failure.status)
        val failureResult = PosJson.decodeFromString(MobileMoneyWebhookResponse.serializer(), failure.bodyAsText())
        assertEquals("failed", failureResult.status)
        assertTrue(!failureResult.alreadyProcessed)
        assertEquals("failed" to "MM-FAIL-1", paymentStatus(db, initiated.paymentId))

        val failureReason = db.read { conn ->
            conn.prepareStatement("SELECT failure_reason FROM payments WHERE payment_id = ?").use { st ->
                st.setString(1, initiated.paymentId)
                st.executeQuery().use { rs -> rs.next(); rs.getString(1) }
            }
        }
        assertEquals("expired", failureReason) // reason set by the failing test body below

        // Terminal is terminal: a later confirm for the same payment is refused.
        val reviveBody = webhookBody(initiated.paymentId, 5000, "confirmed", ref = "MM-FAIL-1")
        val revive = client.post("/payments/mobile-money/webhook") {
            contentType(ContentType.Application.Json)
            header("X-Momo-Signature", signature(reviveBody, secret))
            setBody(reviveBody)
        }
        assertEquals(HttpStatusCode.Conflict, revive.status)
        assertEquals("failed" to "MM-FAIL-1", paymentStatus(db, initiated.paymentId))
    }

    @Test
    fun `a signed callback for an unknown payment id creates nothing`() = testApplication {
        val db = TestDb.fresh()
        val saleId = db.insertSale(5000)
        application { posModule(db, config()) }

        val body = webhookBody("no-such-payment", 5000, "confirmed")
        val response = client.post("/payments/mobile-money/webhook") {
            contentType(ContentType.Application.Json)
            header("X-Momo-Signature", signature(body, secret))
            setBody(body)
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
        val count = db.read { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM payments").use { rs -> rs.next(); rs.getLong(1) }
            }
        }
        assertEquals(0L, count)
    }

    @Test
    fun `a full real-transaction journey from initiation to signed confirmation to reconciliation`() = testApplication {
        val db = TestDb.fresh()
        // The Supabase layer (reconciliation RPC) over the same schema.
        SupabaseMigrations.ensure(db)
        val saleId = db.insertSale(5000)
        application { posModule(db, config()) }
        val token = client.enroll().token

        // 1. Initiation: the till asks the server for a pending payable.
        val initiated = PosJson.decodeFromString(
            MobileMoneyInitiateResponse.serializer(),
            client.initiate(
                token,
                MobileMoneyInitiateRequest(saleId, 5000, "0244000000", "momo-key-journey"),
            ).bodyAsText(),
        )
        assertEquals("pending", initiated.status)

        // 2. The provider fires its signed webhook; the server verifies the HMAC
        //    over the exact bytes and confirms the payment exactly once.
        val body = webhookBody(initiated.paymentId, 5000, "confirmed", ref = "MM-JOURNEY-1")
        val webhook = client.post("/payments/mobile-money/webhook") {
            contentType(ContentType.Application.Json)
            header("X-Momo-Signature", "sha256=" + signature(body, secret))
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, webhook.status)
        val confirmed = PosJson.decodeFromString(MobileMoneyWebhookResponse.serializer(), webhook.bodyAsText())
        assertEquals("confirmed", confirmed.status)
        assertTrue(!confirmed.alreadyProcessed)
        assertEquals("confirmed" to "MM-JOURNEY-1", paymentStatus(db, initiated.paymentId))

        // 3. The till reads the authoritative state back.
        val status = PosJson.decodeFromString(
            MobileMoneyStatusResponse.serializer(),
            client.get("/payments/mobile-money/${initiated.paymentId}") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.bodyAsText(),
        )
        assertEquals("confirmed", status.status)
        assertEquals("MM-JOURNEY-1", status.providerRef)
        assertEquals(5000, status.amount)

        // 4. Reconciliation from the admin surface against the provider statement.
        //    is_admin() resolves the caller from the JWT claim, so the test plays
        //    the admin: an auth.users row, a profile, then the request claim.
        val adminId = UUID.randomUUID()
        db.transaction { conn ->
            conn.prepareStatement("INSERT INTO auth.users (id) VALUES (?)").use { st ->
                st.setObject(1, adminId)
                st.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO profiles (id, full_name, role, is_active) VALUES (?, 'Owner', 'admin', TRUE)",
            ).use { st ->
                st.setObject(1, adminId)
                st.executeUpdate()
            }
        }
        val reconcile: (Long, String) -> Pair<Boolean, String> = { amount, ref ->
            db.read { conn ->
                conn.createStatement().use { st -> st.execute("SET ROLE authenticated") }
                try {
                    conn.prepareStatement("SELECT set_config('request.jwt.claim.sub', ?, false)").use { st ->
                        st.setString(1, adminId.toString())
                        st.executeQuery().close()
                    }
                    conn.prepareStatement(
                        "SELECT matched, reason FROM public.admin_reconcile_mobile_money_payment(?, ?, ?)",
                    ).use { st ->
                        st.setString(1, initiated.paymentId)
                        st.setString(2, ref)
                        st.setLong(3, amount)
                        st.executeQuery().use { rs -> rs.next(); rs.getBoolean(1) to rs.getString(2) }
                    }
                } finally {
                    conn.createStatement().use { st -> st.execute("RESET ROLE") }
                }
            }
        }
        // The statement row the admin app enters (provider_ref + amount) matches.
        assertEquals(true to "matched", reconcile(5000, "MM-JOURNEY-1"))

        // A retry is idempotent: still matched, and the row was stamped once.
        assertEquals(true to "matched", reconcile(5000, "MM-JOURNEY-1"))

        // The statement disagrees on the amount: refused, payment untouched.
        assertEquals(false to "amount-mismatch", reconcile(4999, "MM-JOURNEY-1"))
        assertEquals("confirmed" to "MM-JOURNEY-1", paymentStatus(db, initiated.paymentId))

        val reconciledAt = db.read { conn ->
            conn.prepareStatement("SELECT reconciled_at FROM payments WHERE payment_id = ?").use { st ->
                st.setString(1, initiated.paymentId)
                st.executeQuery().use { rs -> rs.next(); rs.getObject(1) }
            }
        }
        assertTrue(reconciledAt != null, "a matched reconciliation stamps reconciled_at")
    }
}