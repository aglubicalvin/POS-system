package com.businessstore.pos.payments

import com.businessstore.pos.db.Database
import com.businessstore.pos.domain.PosJson
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Mobile-money (MoMo) payment boundary.
 *
 * Deliberately provider-agnostic. A real aggregator (MTN MoMo, Hubtel, Paystack,
 * Flutterwave, ...) plugs in at one seam: the pending payment this service creates
 * is the record the provider's STK prompt refers to, and the provider's signed
 * callback is what flips it to `confirmed`. Nothing here invents a vendor protocol,
 * so no credentials are needed to run the tests.
 *
 * The rules that matter for money:
 *  - initiation is idempotent on the caller's key (a retried tap cannot create a
 *    second payable record);
 *  - only a correctly signed callback may change a payment's status;
 *  - a callback whose amount or provider reference disagrees with the payment is
 *    rejected without mutating anything;
 *  - a replayed callback is a no-op, not a second confirmation.
 */

@Serializable
data class MobileMoneyInitiateRequest(
    val saleId: String,
    val amount: Long,
    val payerMsisdn: String,
    val idempotencyKey: String,
)

@Serializable
data class MobileMoneyInitiateResponse(
    val paymentId: String,
    val status: String,
    val provider: String?,
    /** False when an earlier call with the same idempotency key already created it. */
    val created: Boolean,
)

@Serializable
data class MobileMoneyWebhookRequest(
    val paymentId: String,
    val providerRef: String,
    val amount: Long,
    /** "confirmed" or "failed". */
    val status: String,
    val failureReason: String? = null,
)

@Serializable
data class MobileMoneyWebhookResponse(
    val paymentId: String,
    val status: String,
    val alreadyProcessed: Boolean,
)

@Serializable
data class MobileMoneyStatusResponse(
    val paymentId: String,
    val saleId: String,
    val amount: Long,
    val status: String,
    val provider: String?,
    val providerRef: String?,
)

/** The provider's callback cannot be trusted because no signing secret is configured. */
class WebhookNotConfiguredException : RuntimeException("mobile-money webhook secret is not configured")

/** The callback signature did not match the body. */
class InvalidSignatureException : RuntimeException("invalid mobile-money webhook signature")

/** The callback contradicts the payment it refers to. */
class PaymentConflictException(message: String) : RuntimeException(message)

class PaymentNotFoundException(paymentId: String) : RuntimeException("payment $paymentId not found")

class MobileMoneyService(
    private val db: Database,
    private val webhookSecret: String? = null,
    private val provider: String? = null,
) {
    private val msisdn = Regex("^\\+?[0-9]{9,15}$")

    fun initiate(request: MobileMoneyInitiateRequest): MobileMoneyInitiateResponse {
        require(request.saleId.isNotBlank()) { "saleId is required" }
        require(request.amount > 0) { "amount must be positive" }
        require(msisdn.matches(request.payerMsisdn)) { "payerMsisdn must be a dialable number" }
        require(request.idempotencyKey.length in 8..128) { "idempotencyKey must be 8-128 characters" }

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val paymentId = UUID.randomUUID().toString()

        return db.transaction { conn ->
            val saleTotal = conn.prepareStatement("SELECT total FROM sales WHERE sale_id = ?").use { st ->
                st.setString(1, request.saleId)
                st.executeQuery().use { rs ->
                    if (!rs.next()) throw IllegalArgumentException("sale ${request.saleId} does not exist")
                    rs.getLong(1)
                }
            }
            require(request.amount <= saleTotal) { "amount exceeds the sale total of $saleTotal" }

            val inserted = conn.prepareStatement(
                """
                INSERT INTO payments (payment_id, sale_id, method, amount, status, idempotency_key,
                                      provider, provider_ref, failure_reason, reconciled_at,
                                      created_at, updated_at)
                VALUES (?, ?, 'mobile_money', ?, 'pending', ?, ?, NULL, NULL, NULL, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """.trimIndent(),
            ).use { st ->
                st.setString(1, paymentId)
                st.setString(2, request.saleId)
                st.setLong(3, request.amount)
                st.setString(4, request.idempotencyKey)
                st.setString(5, provider)
                st.setObject(6, now)
                st.setObject(7, now)
                st.executeUpdate()
            }

            if (inserted == 0) {
                // Same key: return the payment that already exists, never a second one.
                conn.prepareStatement(
                    "SELECT payment_id, status FROM payments WHERE idempotency_key = ?",
                ).use { st ->
                    st.setString(1, request.idempotencyKey)
                    st.executeQuery().use { rs ->
                        check(rs.next()) { "idempotent insert lost the existing payment" }
                        MobileMoneyInitiateResponse(rs.getString(1), rs.getString(2), provider, created = false)
                    }
                }
            } else {
                MobileMoneyInitiateResponse(paymentId, "pending", provider, created = true)
            }
        }
    }

    /**
     * Apply the provider's callback. `rawBody` is the exact bytes received — the
     * signature covers those, not a re-serialised object.
     */
    fun handleWebhook(rawBody: String, signature: String?): MobileMoneyWebhookResponse {
        val secret = webhookSecret ?: throw WebhookNotConfiguredException()
        if (!signatureMatches(secret, rawBody, signature)) throw InvalidSignatureException()

        val callback = PosJson.decodeFromString(MobileMoneyWebhookRequest.serializer(), rawBody)
        require(callback.status == "confirmed" || callback.status == "failed") {
            "status must be 'confirmed' or 'failed'"
        }
        require(callback.providerRef.isNotBlank()) { "providerRef is required" }
        require(callback.amount > 0) { "amount must be positive" }

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        return db.transaction { conn ->
            val current = conn.prepareStatement(
                "SELECT status, amount, provider_ref FROM payments WHERE payment_id = ? FOR UPDATE",
            ).use { st ->
                st.setString(1, callback.paymentId)
                st.executeQuery().use { rs ->
                    if (!rs.next()) throw PaymentNotFoundException(callback.paymentId)
                    Triple(rs.getString(1), rs.getLong(2), rs.getString(3))
                }
            }
            val (status, amount, existingRef) = current

            // A callback that contradicts the stored payment is refused untouched.
            if (amount != callback.amount) {
                throw PaymentConflictException(
                    "callback amount ${callback.amount} does not match the payment amount $amount",
                )
            }
            if (existingRef != null && existingRef != callback.providerRef) {
                throw PaymentConflictException(
                    "callback reference ${callback.providerRef} does not match the stored reference $existingRef",
                )
            }

            if (status == callback.status) {
                // Replay: already applied, nothing to write.
                return@transaction MobileMoneyWebhookResponse(callback.paymentId, status, alreadyProcessed = true)
            }
            if (status != "pending") {
                throw PaymentConflictException("payment is $status and cannot become ${callback.status}")
            }

            conn.prepareStatement(
                """
                UPDATE payments
                   SET status = ?, provider_ref = ?, provider = coalesce(provider, ?),
                       failure_reason = ?, updated_at = ?
                 WHERE payment_id = ?
                """.trimIndent(),
            ).use { st ->
                st.setString(1, callback.status)
                st.setString(2, callback.providerRef)
                st.setString(3, provider)
                st.setString(4, callback.failureReason)
                st.setObject(5, now)
                st.setString(6, callback.paymentId)
                st.executeUpdate()
            }

            MobileMoneyWebhookResponse(callback.paymentId, callback.status, alreadyProcessed = false)
        }
    }

    fun status(paymentId: String): MobileMoneyStatusResponse = db.read { conn ->
        conn.prepareStatement(
            """
            SELECT payment_id, sale_id, amount, status, provider, provider_ref
            FROM payments WHERE payment_id = ? AND method = 'mobile_money'
            """.trimIndent(),
        ).use { st ->
            st.setString(1, paymentId)
            st.executeQuery().use { rs ->
                if (!rs.next()) throw PaymentNotFoundException(paymentId)
                MobileMoneyStatusResponse(
                    paymentId = rs.getString(1),
                    saleId = rs.getString(2),
                    amount = rs.getLong(3),
                    status = rs.getString(4),
                    provider = rs.getString(5),
                    providerRef = rs.getString(6),
                )
            }
        }
    }

    private fun signatureMatches(secret: String, body: String, header: String?): Boolean {
        if (header.isNullOrBlank()) return false
        val provided = header.removePrefix("sha256=").trim()
        if (provided.isEmpty()) return false
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val expected = mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            provided.lowercase().toByteArray(Charsets.UTF_8),
        )
    }
}
