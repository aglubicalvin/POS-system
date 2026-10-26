package com.businessstore.pos.sync

import com.businessstore.pos.db.Database
import com.businessstore.pos.domain.ApplyResultDto
import com.businessstore.pos.domain.ClientSaleItem
import com.businessstore.pos.domain.GoodsReceivedPayload
import com.businessstore.pos.domain.LinePrice
import com.businessstore.pos.domain.PaymentUpdatedPayload
import com.businessstore.pos.domain.PosJson
import com.businessstore.pos.domain.ProductUpsertedPayload
import com.businessstore.pos.domain.SaleCreatedPayload
import com.businessstore.pos.domain.SaleRefundedPayload
import com.businessstore.pos.domain.SaleVoidedPayload
import com.businessstore.pos.domain.ShiftClosedPayload
import com.businessstore.pos.domain.ShiftOpenedPayload
import com.businessstore.pos.domain.StockMovementPayload
import com.businessstore.pos.domain.UserUpsertedPayload
import com.businessstore.pos.domain.SyncEventEnvelope
import com.businessstore.pos.domain.priceLine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.OffsetDateTime
import java.util.UUID

/** Raised when an event references a product the server catalog does not have. */
class UnknownProductException(val productId: String, val eventId: String) :
    IllegalStateException("event $eventId references unknown product $productId; sync the catalog first")

/**
 * Raised when an event payload is unacceptable — malformed fields, a plaintext
 * secret, a bound relayed user. The whole apply transaction rolls back, so the
 * event is NOT claimed and the client may retry (or never will, if the sending
 * device is compromised; the flag surfaces and logs either way).
 */
class InvalidEventPayloadException(val eventId: String, message: String) : IllegalStateException(message)

/**
 * Applies device events to the authoritative store.
 *
 * Two invariants, both enforced here rather than trusted from the client:
 *
 *  1. **Prices and totals are recomputed** from the server's own catalog. The
 *     client's numbers are recorded only so a mismatch can be flagged.
 *  2. **Every event applies exactly once.** `processed_events` is claimed with an
 *     INSERT ... ON CONFLICT DO NOTHING inside the event's own transaction, so
 *     the claim and its effects commit together. Two concurrent deliveries of the
 *     same event cannot both win the claim — a stronger guarantee than a
 *     read-then-write existence check.
 *
 * A synced sale is never rejected for insufficient stock: movements apply and any
 * resulting negative on-hand is flagged for review.
 */
class EventApplier(private val db: Database) {

    fun applyBatch(events: List<SyncEventEnvelope>): List<ApplyResultDto> =
        // Per-device sequence order: later events for a device must not land first.
        events.sortedWith(compareBy({ it.deviceId }, { it.seq })).map { apply(it) }

    fun apply(event: SyncEventEnvelope): ApplyResultDto = db.transaction { conn ->
        if (!claim(conn, event)) {
            return@transaction storedResult(conn, event.eventId)
        }
        val result = when (event.eventType) {
            "sale.created" -> applySaleCreated(conn, event)
            "sale.voided" -> applySaleVoided(conn, event)
            "sale.refunded" -> applySaleRefunded(conn, event)
            "goods_received.created" -> applyGoodsReceived(conn, event)
            "stock.movement" -> applyStockMovement(conn, event)
            "shift.opened" -> applyShiftOpened(conn, event)
            "shift.closed" -> applyShiftClosed(conn, event)
            "payment.updated" -> applyPaymentUpdated(conn, event)
            "product.upserted" -> applyProductUpserted(conn, event)
            "user.upserted" -> applyUserUpserted(conn, event)
            else -> ApplyResultDto(eventId = event.eventId, status = "applied")
        }
        storeResult(conn, event.eventId, result)
        result
    }

    // ------------------------------------------------------------- idempotency

    private fun claim(conn: Connection, event: SyncEventEnvelope): Boolean {
        // The full envelope is stored WITH the claim so `GET /sync/pull` can relay
        // it verbatim to the store's other tills (see EventPull).
        val sql = """
            INSERT INTO processed_events
                 (event_id, device_id, seq, event_type, aggregate_type, aggregate_id, payload, applied_at, result)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, now(), '{}'::jsonb)
            ON CONFLICT (event_id) DO NOTHING
        """.trimIndent()
        conn.prepareStatement(sql).use { st ->
            st.setString(1, event.eventId)
            st.setString(2, event.deviceId)
            st.setLong(3, event.seq)
            st.setString(4, event.eventType)
            st.setString(5, event.aggregateType)
            st.setString(6, event.aggregateId)
            st.setString(7, PosJson.encodeToString(JsonObject.serializer(), event.payload))
            return st.executeUpdate() == 1
        }
    }

    private fun storeResult(conn: Connection, eventId: String, result: ApplyResultDto) {
        conn.prepareStatement("UPDATE processed_events SET result = ?::jsonb WHERE event_id = ?").use { st ->
            st.setString(1, PosJson.encodeToString(ApplyResultDto.serializer(), result))
            st.setString(2, eventId)
            st.executeUpdate()
        }
    }

    /**
     * Replay the recorded outcome of an event that was already applied.
     *
     * The stored payload holds the FIRST apply's details (server totals, discrepancy)
     * but its status is "applied" — that describes history, not this delivery. The
     * status is therefore rewritten to "duplicate" so a retrying client can tell
     * that its upload was recognised rather than re-executed.
     */
    private fun storedResult(conn: Connection, eventId: String): ApplyResultDto {
        conn.prepareStatement("SELECT result FROM processed_events WHERE event_id = ?").use { st ->
            st.setString(1, eventId)
            st.executeQuery().use { rs ->
                if (!rs.next()) return ApplyResultDto(eventId = eventId, status = "duplicate")
                val stored = rs.getString(1)
                return runCatching {
                    PosJson.decodeFromString(ApplyResultDto.serializer(), stored)
                }.map { it.copy(status = "duplicate") }
                    .getOrElse { ApplyResultDto(eventId = eventId, status = "duplicate") }
            }
        }
    }

    // -------------------------------------------------------------- sale events

    private data class PricedItem(
        val client: ClientSaleItem,
        val product: ProductRow,
        val unitPrice: Long,
        val taxRateBp: Long,
        val price: LinePrice,
    )

    private fun applySaleCreated(conn: Connection, event: SyncEventEnvelope): ApplyResultDto {
        val payload = PosJson.decodeFromJsonElement<SaleCreatedPayload>(event.payload)
        require(payload.items.isNotEmpty()) { "sale.created ${event.eventId} has no items" }

        val priced = payload.items.map { item ->
            val product = findProduct(conn, item.productId)
                ?: throw UnknownProductException(item.productId, event.eventId)
            // An authorised override keeps the client's price; catalog prices are re-read.
            val isOverride = item.priceSource == "override"
            val unitPrice = if (isOverride) item.unitPrice else product.unitPrice
            val taxRateBp = if (isOverride) item.taxRateBp else product.taxRateBp
            PricedItem(
                client = item,
                product = product,
                unitPrice = unitPrice,
                taxRateBp = taxRateBp,
                price = priceLine(unitPrice, item.quantity, item.discount, taxRateBp),
            )
        }

        val serverTotal = priced.sumOf { it.price.total }
        val discrepancy = serverTotal != payload.clientTotals.total

        if (!saleExists(conn, payload.saleId)) {
            insertSale(
                conn = conn,
                saleId = payload.saleId,
                deviceId = event.deviceId,
                receiptNumber = payload.receiptNumber,
                shiftId = payload.shiftId,
                cashierId = payload.cashierId,
                customerRef = payload.customerRef,
                currency = payload.currency,
                subtotal = payload.clientTotals.subtotal,
                discountTotal = payload.clientTotals.discountTotal,
                taxTotal = payload.clientTotals.taxTotal,
                total = payload.clientTotals.total,
                kind = "sale",
                originalSaleId = null,
                serverTotal = serverTotal,
                discrepancy = discrepancy,
                syncState = if (discrepancy) "disputed" else "synced",
                createdAt = payload.createdAt,
            )
            for (p in priced) {
                insertSaleItem(conn, payload.saleId, p)
            }
        }

        var reviewFlagged = false
        for (p in priced) {
            val flagged = recordMovement(
                conn = conn,
                movementId = null,
                deviceId = event.deviceId,
                productId = p.product.productId,
                quantityDelta = -p.client.quantity,
                reasonCode = "sale",
                refType = "sale",
                refId = payload.saleId,
                userId = payload.cashierId,
                approvalId = null,
                occurredAt = payload.createdAt,
                reviewIfNegative = true,
            )
            if (flagged) reviewFlagged = true
        }

        return ApplyResultDto(
            eventId = event.eventId,
            status = "applied",
            serverTotal = serverTotal,
            clientTotal = payload.clientTotals.total,
            discrepancy = discrepancy,
            reviewFlagged = reviewFlagged,
        )
    }

    private fun applySaleVoided(conn: Connection, event: SyncEventEnvelope): ApplyResultDto {
        val payload = PosJson.decodeFromJsonElement<SaleVoidedPayload>(event.payload)
        val items = conn.prepareStatement("SELECT product_id, quantity FROM sale_items WHERE sale_id = ?").use { st ->
            st.setString(1, payload.originalSaleId)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.getString(1) to rs.getLong(2))
                }
            }
        }
        for ((productId, quantity) in items) {
            recordMovement(
                conn = conn,
                movementId = null,
                deviceId = event.deviceId,
                productId = productId,
                quantityDelta = quantity,
                reasonCode = "void",
                refType = "sale",
                refId = payload.voidSaleId,
                userId = payload.userId,
                approvalId = null,
                occurredAt = payload.createdAt,
                reviewIfNegative = false,
            )
        }
        conn.prepareStatement("UPDATE sales SET status = 'voided' WHERE sale_id = ?").use { st ->
            st.setString(1, payload.originalSaleId)
            st.executeUpdate()
        }
        return ApplyResultDto(eventId = event.eventId, status = "applied")
    }

    private fun applySaleRefunded(conn: Connection, event: SyncEventEnvelope): ApplyResultDto {
        val payload = PosJson.decodeFromJsonElement<SaleRefundedPayload>(event.payload)
        for (line in payload.lines) {
            recordMovement(
                conn = conn,
                movementId = null,
                deviceId = event.deviceId,
                productId = line.productId,
                quantityDelta = line.quantity,
                reasonCode = "refund",
                refType = "sale",
                refId = payload.refundSaleId,
                userId = payload.userId,
                approvalId = null,
                occurredAt = payload.createdAt,
                reviewIfNegative = false,
            )
        }
        conn.prepareStatement("UPDATE sales SET status = 'refunded' WHERE sale_id = ?").use { st ->
            st.setString(1, payload.originalSaleId)
            st.executeUpdate()
        }
        return ApplyResultDto(eventId = event.eventId, status = "applied")
    }

    // --------------------------------------------------------- inventory events

    private fun applyGoodsReceived(conn: Connection, event: SyncEventEnvelope): ApplyResultDto {
        val payload = PosJson.decodeFromJsonElement<GoodsReceivedPayload>(event.payload)
        for (line in payload.lines) {
            recordMovement(
                conn = conn,
                movementId = null,
                deviceId = event.deviceId,
                productId = line.productId,
                quantityDelta = line.quantity,
                reasonCode = "goods_received",
                refType = "goods_received",
                refId = payload.grnId,
                userId = payload.receivedBy,
                approvalId = null,
                occurredAt = payload.receivedAt,
                reviewIfNegative = false,
            )
        }
        return ApplyResultDto(eventId = event.eventId, status = "applied")
    }

    private fun applyStockMovement(conn: Connection, event: SyncEventEnvelope): ApplyResultDto {
        val payload = PosJson.decodeFromJsonElement<StockMovementPayload>(event.payload)
        val flagged = recordMovement(
            conn = conn,
            movementId = payload.movementId,
            deviceId = event.deviceId,
            productId = payload.productId,
            quantityDelta = payload.quantityDelta,
            reasonCode = payload.reasonCode,
            refType = "manual",
            refId = null,
            userId = payload.userId,
            approvalId = payload.approvalId,
            occurredAt = payload.occurredAt,
            reviewIfNegative = true,
        )
        return ApplyResultDto(eventId = event.eventId, status = "applied", reviewFlagged = flagged)
    }

    // ------------------------------------------------------------- shift events

    private fun applyShiftOpened(conn: Connection, event: SyncEventEnvelope): ApplyResultDto {
        val payload = PosJson.decodeFromJsonElement<ShiftOpenedPayload>(event.payload)
        val sql = """
            INSERT INTO shifts (shift_id, device_id, user_id, opened_at, closed_at, opening_float,
                                cash_count, expected_cash, variance, status, approved_by)
            VALUES (?, ?, ?, ?, NULL, ?, NULL, NULL, NULL, 'open', NULL)
            ON CONFLICT (shift_id) DO NOTHING
        """.trimIndent()
        conn.prepareStatement(sql).use { st ->
            st.setString(1, payload.shiftId)
            st.setString(2, event.deviceId)
            st.setString(3, payload.userId)
            st.setInstant(4, payload.openedAt)
            st.setLong(5, payload.openingFloat)
            st.executeUpdate()
        }
        return ApplyResultDto(eventId = event.eventId, status = "applied")
    }

    private fun applyShiftClosed(conn: Connection, event: SyncEventEnvelope): ApplyResultDto {
        val payload = PosJson.decodeFromJsonElement<ShiftClosedPayload>(event.payload)
        val sql = """
            UPDATE shifts SET closed_at = ?, cash_count = ?, expected_cash = ?, variance = ?, status = 'closed'
             WHERE shift_id = ?
        """.trimIndent()
        conn.prepareStatement(sql).use { st ->
            st.setInstant(1, payload.closedAt)
            st.setLong(2, payload.cashCount)
            st.setLong(3, payload.expected)
            st.setLong(4, payload.variance)
            st.setString(5, payload.shiftId)
            st.executeUpdate()
        }
        return ApplyResultDto(eventId = event.eventId, status = "applied")
    }

    private fun applyPaymentUpdated(conn: Connection, event: SyncEventEnvelope): ApplyResultDto {
        val payload = PosJson.decodeFromJsonElement<PaymentUpdatedPayload>(event.payload)
        // The provider is the source of truth for a confirmed payment; record the
        // transition if the sale is known, never create a payment out of thin air.
        val sql = """
            UPDATE payments SET status = ?, provider_ref = COALESCE(?, provider_ref), updated_at = now()
             WHERE payment_id = ? AND status <> ?
        """.trimIndent()
        conn.prepareStatement(sql).use { st ->
            st.setString(1, payload.status)
            st.setString(2, payload.providerRef)
            st.setString(3, payload.paymentId)
            st.setString(4, payload.status)
            st.executeUpdate()
        }
        return ApplyResultDto(eventId = event.eventId, status = "applied")
    }

    // -------------------------------------------------------- catalog/user events

    private fun applyProductUpserted(conn: Connection, event: SyncEventEnvelope): ApplyResultDto {
        val payload = runCatching {
            PosJson.decodeFromJsonElement<ProductUpsertedPayload>(event.payload)
        }.getOrElse { throw InvalidEventPayloadException(event.eventId, "event ${event.eventId}: unreadable product.upserted payload (${it.message})") }

        validated(event) {
            require(payload.productId.isNotBlank()) { "missing product_id" }
            require(payload.sku.isNotBlank()) { "missing or blank sku" }
            require(payload.unitPrice >= 0) { "unit_price must be a non-negative integer" }
            require(payload.costPrice >= 0) { "cost_price must be a non-negative integer" }
            require(payload.taxRateBp in 0..100_000) { "tax_rate_bp must be between 0 and 10000 basis points" }
            require(payload.lowStockThreshold >= 0) { "low_stock_threshold must be a non-negative integer" }
        }

        val name = payload.name?.takeIf { it.isNotBlank() } ?: payload.sku
        // The envelope's deviceId is authoritative (the route already refuses
        // events claiming another device); the payload's field only says where
        // the row was FIRST created and is relayed verbatim to the other tills.
        val sql = """
            INSERT INTO products (product_id, device_id, sku, name, unit_price, cost_price, tax_rate_bp,
                                  low_stock_threshold, is_active, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, now()), ?)
            ON CONFLICT (product_id) DO UPDATE SET
              sku = EXCLUDED.sku, name = EXCLUDED.name, unit_price = EXCLUDED.unit_price,
              cost_price = EXCLUDED.cost_price, tax_rate_bp = EXCLUDED.tax_rate_bp,
              low_stock_threshold = EXCLUDED.low_stock_threshold, is_active = EXCLUDED.is_active,
              updated_at = EXCLUDED.updated_at
        """.trimIndent()
        conn.prepareStatement(sql).use { st ->
            st.setString(1, payload.productId)
            st.setString(2, event.deviceId)
            st.setString(3, payload.sku.trim())
            st.setString(4, name)
            st.setLong(5, payload.unitPrice)
            st.setLong(6, payload.costPrice)
            st.setLong(7, payload.taxRateBp)
            st.setLong(8, payload.lowStockThreshold)
            st.setBoolean(9, payload.isActive != 0L)
            st.setObject(10, instantOrNull(payload.createdAt, event) ?: OffsetDateTime.now())
            st.setObject(11, instantOrNull(payload.updatedAt ?: payload.createdAt, event) ?: OffsetDateTime.now())
            st.executeUpdate()
        }
        return ApplyResultDto(eventId = event.eventId, status = "applied")
    }

    /** Same rule as the device's own dispatcher: only scrubbed secret formats. */
    private fun looksHashed(secret: String): Boolean =
        secret.startsWith("scrypt\$") || secret.startsWith("\$argon2")

    private fun applyUserUpserted(conn: Connection, event: SyncEventEnvelope): ApplyResultDto {
        val payload = runCatching {
            PosJson.decodeFromJsonElement<UserUpsertedPayload>(event.payload)
        }.getOrElse { throw InvalidEventPayloadException(event.eventId, "event ${event.eventId}: unreadable user.upserted payload (${it.message})") }

        validated(event) {
            require(payload.userId.isNotBlank()) { "missing user_id" }
            require(payload.fullName.isNotBlank()) { "missing full_name" }
            require(payload.role in ROLES) { "invalid role: ${payload.role}" }
        }
        // Relayed users are store-global: a device-bound row would be unjoinable
        // for the sending till's id AND unusable (verifyPin refuses cross-till
        // PINs by design). The device never ships its binding; refuse it here.
        validated(event) {
            require(payload.deviceId == null) { "binds the user to a device; relayed users must be store-global" }
            require(payload.failedAttempts >= 0) { "failed_attempts must be non-negative" }
        }
        val pinOk = payload.pinHash?.let(::looksHashed) ?: true
        val passwordOk = payload.passwordHash?.let(::looksHashed) ?: true
        validated(event) {
            require(pinOk) { "carries a plaintext pin_hash; only hashed secrets may be stored" }
            require(passwordOk) { "carries a plaintext password_hash; only hashed secrets may be stored" }
        }

        val sql = """
            INSERT INTO users (user_id, device_id, full_name, role, pin_hash, password_hash, mfa_enabled,
                               failed_attempts, locked_until, is_active, created_at, updated_at)
            VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, now()), ?)
            ON CONFLICT (user_id) DO UPDATE SET
              full_name = EXCLUDED.full_name, role = EXCLUDED.role, pin_hash = EXCLUDED.pin_hash,
              password_hash = EXCLUDED.password_hash, mfa_enabled = EXCLUDED.mfa_enabled,
              failed_attempts = EXCLUDED.failed_attempts, locked_until = EXCLUDED.locked_until,
              is_active = EXCLUDED.is_active, updated_at = EXCLUDED.updated_at
        """.trimIndent()
        return conn.prepareStatement(sql).use { st ->
            st.setString(1, payload.userId)
            st.setString(2, payload.fullName.trim())
            st.setString(3, payload.role)
            if (payload.pinHash == null) st.setNull(4, java.sql.Types.VARCHAR) else st.setString(4, payload.pinHash)
            if (payload.passwordHash == null) st.setNull(5, java.sql.Types.VARCHAR) else st.setString(5, payload.passwordHash)
            st.setBoolean(6, payload.mfaEnabled != 0L)
            st.setLong(7, payload.failedAttempts)
            if (payload.lockedUntil == null) st.setNull(8, java.sql.Types.TIMESTAMP) else st.setObject(8, instant(payload.lockedUntil, event))
            st.setBoolean(9, payload.isActive != 0L)
            st.setObject(10, instantOrNull(payload.createdAt, event) ?: OffsetDateTime.now())
            st.setObject(11, instantOrNull(payload.updatedAt ?: payload.createdAt, event) ?: OffsetDateTime.now())
            st.executeUpdate()
            ApplyResultDto(eventId = event.eventId, status = "applied")
        }
    }

    // ------------------------------------------------------------ catalog reads

    data class ProductRow(
        val productId: String,
        val sku: String,
        val name: String,
        val unitPrice: Long,
        val taxRateBp: Long,
        val isActive: Boolean,
    )

    private fun findProduct(conn: Connection, productId: String): ProductRow? =
        conn.prepareStatement(
            "SELECT product_id, sku, name, unit_price, tax_rate_bp, is_active FROM products WHERE product_id = ?",
        ).use { st ->
            st.setString(1, productId)
            st.executeQuery().use { rs ->
                if (!rs.next()) null
                else ProductRow(
                    productId = rs.getString("product_id"),
                    sku = rs.getString("sku"),
                    name = rs.getString("name"),
                    unitPrice = rs.getLong("unit_price"),
                    taxRateBp = rs.getLong("tax_rate_bp"),
                    isActive = rs.getBoolean("is_active"),
                )
            }
        }

    private fun saleExists(conn: Connection, saleId: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM sales WHERE sale_id = ?").use { st ->
            st.setString(1, saleId)
            st.executeQuery().use { it.next() }
        }

    // ------------------------------------------------------------- sale writes

    private fun insertSale(
        conn: Connection,
        saleId: String,
        deviceId: String,
        receiptNumber: String,
        shiftId: String?,
        cashierId: String,
        customerRef: String?,
        currency: String,
        subtotal: Long,
        discountTotal: Long,
        taxTotal: Long,
        total: Long,
        kind: String,
        originalSaleId: String?,
        serverTotal: Long?,
        discrepancy: Boolean,
        syncState: String,
        createdAt: String,
    ) {
        val sql = """
            INSERT INTO sales (sale_id, device_id, receipt_number, shift_id, cashier_id, customer_ref, currency,
                               subtotal, discount_total, tax_total, total, kind, original_sale_id, status,
                               voided_by, voided_reason, server_total, price_discrepancy, sync_state, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'completed', NULL, NULL, ?, ?, ?, ?)
        """.trimIndent()
        conn.prepareStatement(sql).use { st ->
            st.setString(1, saleId)
            st.setString(2, deviceId)
            st.setString(3, receiptNumber)
            st.setString(4, shiftId)
            st.setString(5, cashierId)
            st.setString(6, customerRef)
            st.setString(7, currency)
            st.setLong(8, subtotal)
            st.setLong(9, discountTotal)
            st.setLong(10, taxTotal)
            st.setLong(11, total)
            st.setString(12, kind)
            st.setString(13, originalSaleId)
            if (serverTotal == null) st.setNull(14, java.sql.Types.BIGINT) else st.setLong(14, serverTotal)
            st.setBoolean(15, discrepancy)
            st.setString(16, syncState)
            st.setInstant(17, createdAt)
            st.executeUpdate()
        }
    }

    private fun insertSaleItem(conn: Connection, saleId: String, p: PricedItem) {
        val sql = """
            INSERT INTO sale_items (item_id, sale_id, product_id, sku, name, quantity, unit_price, discount,
                                    tax_rate_bp, tax, line_total, price_source, override_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
        """.trimIndent()
        conn.prepareStatement(sql).use { st ->
            st.setString(1, p.client.itemId ?: UUID.randomUUID().toString())
            st.setString(2, saleId)
            st.setString(3, p.product.productId)
            st.setString(4, p.client.sku ?: p.product.sku)
            st.setString(5, p.client.name ?: p.product.name)
            st.setLong(6, p.client.quantity)
            st.setLong(7, p.unitPrice)
            st.setLong(8, p.client.discount)
            st.setLong(9, p.taxRateBp)
            st.setLong(10, p.price.tax)
            st.setLong(11, p.price.total)
            st.setString(12, if (p.client.priceSource == "override") "override" else "catalog")
            st.executeUpdate()
        }
    }

    // ------------------------------------------------------------ stock writes

    /**
     * Append a movement and maintain the on-hand cache in the same statement flow.
     * The upsert adds the delta in SQL (`stock_on_hand.quantity + EXCLUDED.quantity`)
     * rather than reading then writing, so it cannot lose an update.
     *
     * @return true when this movement drove on-hand negative and flagged it.
     */
    private fun recordMovement(
        conn: Connection,
        movementId: String?,
        deviceId: String,
        productId: String,
        quantityDelta: Long,
        reasonCode: String,
        refType: String?,
        refId: String?,
        userId: String,
        approvalId: String?,
        occurredAt: String,
        reviewIfNegative: Boolean,
    ): Boolean {
        require(quantityDelta != 0L) { "quantityDelta must be non-zero" }

        val current = conn.prepareStatement("SELECT quantity FROM stock_on_hand WHERE product_id = ?").use { st ->
            st.setString(1, productId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
        val requiresReview = reviewIfNegative && (current + quantityDelta) < 0

        val insertSql = """
            INSERT INTO stock_movements (movement_id, device_id, product_id, quantity_delta, reason_code,
                                         ref_type, ref_id, user_id, approval_id, requires_review,
                                         occurred_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
        """.trimIndent()
        conn.prepareStatement(insertSql).use { st ->
            st.setString(1, movementId ?: UUID.randomUUID().toString())
            st.setString(2, deviceId)
            st.setString(3, productId)
            st.setLong(4, quantityDelta)
            st.setString(5, reasonCode)
            st.setString(6, refType)
            st.setString(7, refId)
            st.setString(8, userId)
            st.setString(9, approvalId)
            st.setBoolean(10, requiresReview)
            st.setInstant(11, occurredAt)
            st.executeUpdate()
        }

        val upsertSql = """
            INSERT INTO stock_on_hand (product_id, quantity, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT (product_id) DO UPDATE SET
              quantity = stock_on_hand.quantity + EXCLUDED.quantity,
              updated_at = EXCLUDED.updated_at
        """.trimIndent()
        conn.prepareStatement(upsertSql).use { st ->
            st.setString(1, productId)
            st.setLong(2, quantityDelta)
            st.setInstant(3, occurredAt)
            st.executeUpdate()
        }

        return requiresReview
    }
}

/** The roles a user row may carry; mirrors the schema CHECK. */
private val ROLES = setOf("cashier", "manager", "admin")

/** Every `require(_)` inside `block` becomes a per-event 422, not a bare 400. */
private inline fun validated(event: SyncEventEnvelope, block: () -> Unit) {
    try {
        block()
    } catch (error: IllegalArgumentException) {
        throw InvalidEventPayloadException(event.eventId, "event ${event.eventId}: ${error.message}")
    }
}

/** Bind an ISO-8601 instant as a real timestamptz rather than a string cast. */
private fun PreparedStatement.setInstant(index: Int, iso: String) {
    setObject(index, OffsetDateTime.parse(iso))
}

private fun instantOrNull(iso: String?, event: SyncEventEnvelope): OffsetDateTime? =
    iso?.let { instant(it, event) }

/** A payload timestamp the JVM cannot parse is an invalid payload, not an outage. */
private fun instant(iso: String, event: SyncEventEnvelope): OffsetDateTime =
    runCatching { OffsetDateTime.parse(iso) }.getOrElse {
        throw InvalidEventPayloadException(event.eventId, "event ${event.eventId}: unparseable timestamp '$iso'")
    }
