package com.businessstore.pos.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The wire contract between the TypeScript till and this Kotlin server.
 *
 * These types mirror the payloads built in the client's outbox
 * (`src/domain/outbox.ts` and `src/domain/checkout.ts`). Field names are
 * camelCase to match the client exactly — this is a cross-language contract, so
 * `ignoreUnknownKeys` lets the client add fields without breaking the server.
 */
val PosJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}

@Serializable
data class SyncEventEnvelope(
    val eventId: String,
    val deviceId: String,
    val seq: Long,
    val eventType: String,
    val aggregateType: String? = null,
    val aggregateId: String,
    val payload: JsonObject,
    val createdAt: String? = null,
)

@Serializable
data class SyncBatchRequest(val events: List<SyncEventEnvelope>)

@Serializable
data class ApplyResultDto(
    /** "applied" the first time, "duplicate" when replayed. */
    val eventId: String,
    val status: String,
    val serverTotal: Long? = null,
    val clientTotal: Long? = null,
    val discrepancy: Boolean? = null,
    val reviewFlagged: Boolean? = null,
)

@Serializable
data class SyncBatchResponse(val results: List<ApplyResultDto>)

/** Body of `GET /sync/pull`: envelopes relayed from the store's other devices. */
@Serializable
data class PullResponse(val events: List<SyncEventEnvelope>)

// ----------------------------------------------------------------- sale events

@Serializable
data class ClientTotals(
    val subtotal: Long,
    val discountTotal: Long,
    val taxTotal: Long,
    val total: Long,
)

@Serializable
data class ClientSaleItem(
    val itemId: String? = null,
    val productId: String,
    val sku: String? = null,
    val name: String? = null,
    val quantity: Long,
    val unitPrice: Long,
    val discount: Long = 0,
    val taxRateBp: Long = 0,
    val tax: Long? = null,
    val lineTotal: Long? = null,
    val priceSource: String? = null,
)

@Serializable
data class SaleCreatedPayload(
    val saleId: String,
    val receiptNumber: String,
    val shiftId: String? = null,
    val cashierId: String,
    val customerRef: String? = null,
    val currency: String = "GHS",
    val createdAt: String,
    val clientTotals: ClientTotals,
    val items: List<ClientSaleItem>,
)

@Serializable
data class SaleVoidedPayload(
    val voidSaleId: String,
    val originalSaleId: String,
    val receiptNumber: String? = null,
    val userId: String,
    val reason: String? = null,
    val createdAt: String,
    val total: Long? = null,
)

@Serializable
data class RefundLinePayload(
    val productId: String,
    val quantity: Long,
    val lineTotal: Long? = null,
)

@Serializable
data class SaleRefundedPayload(
    val refundSaleId: String,
    val originalSaleId: String,
    val receiptNumber: String? = null,
    val userId: String,
    val reason: String? = null,
    val createdAt: String,
    val refundedTotal: Long? = null,
    val lines: List<RefundLinePayload>,
)

// -------------------------------------------------------------- inventory/sync

@Serializable
data class GoodsReceivedLine(
    val productId: String,
    val quantity: Long,
    val unitCost: Long,
)

@Serializable
data class GoodsReceivedPayload(
    val grnId: String,
    val supplierId: String,
    val receivedBy: String,
    val receivedAt: String,
    val reference: String? = null,
    val totalCost: Long? = null,
    val lines: List<GoodsReceivedLine>,
)

@Serializable
data class StockMovementPayload(
    val movementId: String,
    val productId: String,
    val quantityDelta: Long,
    val reasonCode: String,
    val reason: String? = null,
    val userId: String,
    val approvalId: String? = null,
    val occurredAt: String,
)

@Serializable
data class ShiftOpenedPayload(
    val shiftId: String,
    val userId: String,
    val openingFloat: Long,
    val openedAt: String,
)

@Serializable
data class ShiftClosedPayload(
    val shiftId: String,
    val userId: String,
    val cashCount: Long,
    val expected: Long,
    val variance: Long,
    val closedAt: String,
)

@Serializable
data class PaymentUpdatedPayload(
    val paymentId: String,
    val saleId: String,
    val status: String,
    val providerRef: String? = null,
)

// ------------------------------------------------------------------- API types

@Serializable
data class RegisterDeviceRequest(
    val storeId: String,
    val name: String,
    val model: String? = null,
    val deviceId: String? = null,
)

@Serializable
data class RegisterDeviceResponse(
    val deviceId: String,
    /** Returned exactly once. The client stores it in the OS keystore. */
    val token: String,
    val expiresAt: String,
)

@Serializable
data class DailySalesDto(
    val day: String,
    val salesCount: Long,
    val grossTotal: Long,
    val discountTotal: Long,
    val taxTotal: Long,
)

@Serializable
data class StockLevelDto(
    val productId: String,
    val sku: String,
    val name: String,
    val onHand: Long,
    val threshold: Long,
    val low: Boolean,
)

// --------------------------------------------------------- catalog/user events

/**
 * Catalog and user rows travel in the DEVICE's own column names (snake_case),
 * not the camelCase of the sale payloads above: a `product.upserted` / `user.upserted`
 * event carries the full row exactly as the emitting device stores it, and the
 * server relays it verbatim to the store's other tills via `GET /sync/pull`.
 * On the device these payloads validate and upsert through `src/sync/apply.ts`.
 */
@Serializable
data class ProductUpsertedPayload(
    @SerialName("product_id") val productId: String,
    /** Originating device; informational on ingest (the envelope's deviceId is). */
    @SerialName("device_id") val deviceId: String? = null,
    val sku: String,
    val name: String? = null,
    @SerialName("unit_price") val unitPrice: Long,
    @SerialName("cost_price") val costPrice: Long = 0,
    @SerialName("tax_rate_bp") val taxRateBp: Long = 0,
    @SerialName("low_stock_threshold") val lowStockThreshold: Long = 0,
    /** SQLite 0/1 on the wire. */
    @SerialName("is_active") val isActive: Long = 1,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class UserUpsertedPayload(
    @SerialName("user_id") val userId: String,
    /** Must be ABSENT/null: relayed users are store-global (see apply.ts). */
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("full_name") val fullName: String,
    val role: String,
    @SerialName("pin_hash") val pinHash: String? = null,
    @SerialName("password_hash") val passwordHash: String? = null,
    @SerialName("mfa_enabled") val mfaEnabled: Long = 0,
    @SerialName("failed_attempts") val failedAttempts: Long = 0,
    @SerialName("locked_until") val lockedUntil: String? = null,
    @SerialName("is_active") val isActive: Long = 1,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class ErrorDto(val error: String, val eventId: String? = null)
