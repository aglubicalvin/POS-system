package com.businessstore.pos

import com.businessstore.pos.db.Database
import com.businessstore.pos.db.Schema
import com.businessstore.pos.domain.ClientSaleItem
import com.businessstore.pos.domain.ClientTotals
import com.businessstore.pos.domain.GoodsReceivedLine
import com.businessstore.pos.domain.GoodsReceivedPayload
import com.businessstore.pos.domain.PosJson
import com.businessstore.pos.domain.ProductUpsertedPayload
import com.businessstore.pos.domain.SaleCreatedPayload
import com.businessstore.pos.domain.SyncEventEnvelope
import com.businessstore.pos.domain.UserUpsertedPayload
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import javax.sql.DataSource
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * One embedded PostgreSQL for the whole test JVM.
 *
 * `initdb` costs ~30 seconds, so it is started once lazily and torn down by a
 * shutdown hook. Each test starts from a truncated database, which is safe because
 * JUnit runs test classes sequentially in a single fork.
 */
object TestDb {
    private val pg: EmbeddedPostgres by lazy {
        val instance = EmbeddedPostgres.builder().start()
        Runtime.getRuntime().addShutdownHook(
            Thread { runCatching { instance.close() } },
        )
        instance
    }

    val dataSource: DataSource get() = pg.postgresDatabase

    /**
     * TCP port of the embedded server. Child processes (the standalone tools in
     * `server/tools`) need an address rather than a DataSource. The server listens
     * on loopback only.
     */
    val port: Int by lazy {
        dataSource.connection.use { conn ->
            conn.metaData.url.substringAfter("//").substringAfter(':').substringBefore('/').toInt()
        }
    }

    private val TABLES = listOf(
        "approvals", "audit_log", "device_tokens", "devices", "goods_received_items",
        "goods_received", "outbox", "payments", "processed_events", "products",
        "receipt_counters", "sale_items", "sales", "shifts", "stock_movements",
        "stock_on_hand", "suppliers", "users",
    )

    /** A database with the schema applied and no rows. */
    fun fresh(): Database {
        val db = Database.from(dataSource)
        Schema.apply(db)
        db.transaction { conn ->
            conn.createStatement().use { it.execute("TRUNCATE ${TABLES.joinToString(", ")} CASCADE") }
        }
        return db
    }
}

data class ServerSeed(
    val deviceId: String,
    val otherDeviceId: String,
    val cashierId: String,
    val managerId: String,
    val waterId: String,
    val miloId: String,
    val supplierId: String,
)

/** Seed the authoritative catalog and users the device events will reference. */
fun seedServer(
    db: Database,
    waterPrice: Long = 150,
    miloPrice: Long = 4500,
    miloTaxBp: Long = 1250,
): ServerSeed {
    val seed = ServerSeed(
        deviceId = UUID.randomUUID().toString(),
        otherDeviceId = UUID.randomUUID().toString(),
        cashierId = UUID.randomUUID().toString(),
        managerId = UUID.randomUUID().toString(),
        waterId = UUID.randomUUID().toString(),
        miloId = UUID.randomUUID().toString(),
        supplierId = UUID.randomUUID().toString(),
    )
    val now = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()
    db.transaction { conn ->
        listOf(seed.deviceId to "TILL-1", seed.otherDeviceId to "TILL-2").forEach { (id, name) ->
            conn.prepareStatement(
                """
                INSERT INTO devices (device_id, store_id, name, model, registered_at, last_seen_at,
                                     token_expires_at, is_active)
                VALUES (?, 'store-accra-01', ?, 'tablet', ?, NULL, NULL, TRUE)
                """.trimIndent(),
            ).use { st ->
                st.setString(1, id)
                st.setString(2, name)
                st.setObject(3, java.time.OffsetDateTime.parse(now))
                st.executeUpdate()
            }
        }
        listOf(seed.cashierId to "cashier", seed.managerId to "manager").forEach { (id, role) ->
            conn.prepareStatement(
                """
                INSERT INTO users (user_id, device_id, full_name, role, mfa_enabled, failed_attempts,
                                   is_active, created_at, updated_at)
                VALUES (?, NULL, ?, ?, FALSE, 0, TRUE, ?, ?)
                """.trimIndent(),
            ).use { st ->
                st.setString(1, id)
                st.setString(2, "User $role")
                st.setString(3, role)
                st.setObject(4, java.time.OffsetDateTime.parse(now))
                st.setObject(5, java.time.OffsetDateTime.parse(now))
                st.executeUpdate()
            }
        }
        conn.prepareStatement(
            """
            INSERT INTO suppliers (supplier_id, device_id, name, is_active, created_at, updated_at)
            VALUES (?, ?, 'Accra Wholesale Ltd', TRUE, ?, ?)
            """.trimIndent(),
        ).use { st ->
            st.setString(1, seed.supplierId)
            st.setString(2, seed.deviceId)
            st.setObject(3, java.time.OffsetDateTime.parse(now))
            st.setObject(4, java.time.OffsetDateTime.parse(now))
            st.executeUpdate()
        }
        listOf(
            Triple(seed.waterId, "WATER-500", "Sachet Water 500ml") to (waterPrice to 0L),
            Triple(seed.miloId, "MILO-400", "Milo Refill 400g") to (miloPrice to miloTaxBp),
        ).forEach { (product, pricing) ->
            conn.prepareStatement(
                """
                INSERT INTO products (product_id, device_id, sku, name, unit_price, cost_price, tax_rate_bp,
                                      low_stock_threshold, is_active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, ?, 0, TRUE, ?, ?)
                """.trimIndent(),
            ).use { st ->
                st.setString(1, product.first)
                st.setString(2, seed.deviceId)
                st.setString(3, product.second)
                st.setString(4, product.third)
                st.setLong(5, pricing.first)
                st.setLong(6, pricing.second)
                st.setObject(7, java.time.OffsetDateTime.parse(now))
                st.setObject(8, java.time.OffsetDateTime.parse(now))
                st.executeUpdate()
            }
        }
    }
    return seed
}

inline fun <reified T> envelope(
    eventId: String,
    deviceId: String,
    seq: Long,
    eventType: String,
    aggregateId: String,
    payload: T,
): SyncEventEnvelope = SyncEventEnvelope(
    eventId = eventId,
    deviceId = deviceId,
    seq = seq,
    eventType = eventType,
    aggregateId = aggregateId,
    payload = PosJson.encodeToJsonElement(payload).jsonObject,
)

fun newId(): String = UUID.randomUUID().toString()

fun instantNow(): String = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()

/** Build a `sale.created` event exactly as the TypeScript client would. */
fun saleCreatedEvent(
    seed: ServerSeed,
    saleId: String = newId(),
    receiptNumber: String = "TILL0001-20261009-0001",
    items: List<ClientSaleItem>,
    clientTotals: ClientTotals,
    deviceId: String = seed.deviceId,
    seq: Long = 1,
    createdAt: String = instantNow(),
): SyncEventEnvelope = envelope(
    eventId = newId(),
    deviceId = deviceId,
    seq = seq,
    eventType = "sale.created",
    aggregateId = saleId,
    payload = SaleCreatedPayload(
        saleId = saleId,
        receiptNumber = receiptNumber,
        cashierId = seed.cashierId,
        createdAt = createdAt,
        clientTotals = clientTotals,
        items = items,
    ),
)

/** Build a `product.upserted` event as the TypeScript client produces it. */
fun productUpsertedEvent(
    seed: ServerSeed,
    productId: String,
    sku: String,
    unitPrice: Long,
    name: String? = null,
    deviceId: String = seed.deviceId,
    seq: Long = 1,
    taxRateBp: Long = 0,
    costPrice: Long = 0,
    lowStockThreshold: Long = 0,
    isActive: Long = 1,
): SyncEventEnvelope {
    val at = instantNow()
    return envelope(
        eventId = newId(),
        deviceId = deviceId,
        seq = seq,
        eventType = "product.upserted",
        aggregateId = productId,
        payload = ProductUpsertedPayload(
            productId = productId,
            deviceId = deviceId,
            sku = sku,
            name = name,
            unitPrice = unitPrice,
            costPrice = costPrice,
            taxRateBp = taxRateBp,
            lowStockThreshold = lowStockThreshold,
            isActive = isActive,
            createdAt = at,
            updatedAt = at,
        ),
    )
}

/** Build a `user.upserted` event as the TypeScript client produces it. */
fun userUpsertedEvent(
    seed: ServerSeed,
    userId: String,
    fullName: String,
    role: String,
    pinHash: String? = null,
    passwordHash: String? = null,
    deviceId: String = seed.deviceId,
    seq: Long = 1,
    payloadDeviceId: String? = null,
): SyncEventEnvelope {
    val at = instantNow()
    return envelope(
        eventId = newId(),
        deviceId = deviceId,
        seq = seq,
        eventType = "user.upserted",
        aggregateId = userId,
        payload = UserUpsertedPayload(
            userId = userId,
            deviceId = payloadDeviceId,
            fullName = fullName,
            role = role,
            pinHash = pinHash,
            passwordHash = passwordHash,
            createdAt = at,
            updatedAt = at,
        ),
    )
}

fun goodsReceivedEvent(
    seed: ServerSeed,
    grnId: String = newId(),
    productId: String,
    quantity: Long,
    unitCost: Long = 90,
    deviceId: String = seed.deviceId,
    seq: Long = 1,
): SyncEventEnvelope = envelope(
    eventId = newId(),
    deviceId = deviceId,
    seq = seq,
    eventType = "goods_received.created",
    aggregateId = grnId,
    payload = GoodsReceivedPayload(
        grnId = grnId,
        supplierId = seed.supplierId,
        receivedBy = seed.managerId,
        receivedAt = instantNow(),
        reference = "INV-1",
        totalCost = unitCost * quantity,
        lines = listOf(GoodsReceivedLine(productId = productId, quantity = quantity, unitCost = unitCost)),
    ),
)
