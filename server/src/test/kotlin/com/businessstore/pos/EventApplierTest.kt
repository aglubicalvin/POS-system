package com.businessstore.pos

import com.businessstore.pos.db.Database
import com.businessstore.pos.domain.ClientSaleItem
import com.businessstore.pos.domain.ClientTotals
import com.businessstore.pos.reports.Reports
import com.businessstore.pos.sync.EventApplier
import com.businessstore.pos.sync.InvalidEventPayloadException
import com.businessstore.pos.sync.UnknownProductException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private data class SaleRow(
    val total: Long,
    val serverTotal: Long?,
    val discrepancy: Boolean,
    val syncState: String,
    val status: String,
)

private fun Database.saleRow(saleId: String): SaleRow? = read { conn ->
    conn.prepareStatement(
        "SELECT total, server_total, price_discrepancy, sync_state, status FROM sales WHERE sale_id = ?",
    ).use { st ->
        st.setString(1, saleId)
        st.executeQuery().use { rs ->
            if (!rs.next()) null
            else {
                val serverTotal = rs.getLong("server_total").let { if (rs.wasNull()) null else it }
                SaleRow(
                    total = rs.getLong("total"),
                    serverTotal = serverTotal,
                    discrepancy = rs.getBoolean("price_discrepancy"),
                    syncState = rs.getString("sync_state"),
                    status = rs.getString("status"),
                )
            }
        }
    }
}

private fun Database.count(table: String): Long = read { conn ->
    conn.prepareStatement("SELECT COUNT(*) FROM $table").use { st ->
        st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
    }
}

private fun Database.processedCount(eventId: String): Long = read { conn ->
    conn.prepareStatement("SELECT COUNT(*) FROM processed_events WHERE event_id = ?").use { st ->
        st.setString(1, eventId)
        st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
    }
}

private fun Database.insertProduct(productId: String, deviceId: String, sku: String, name: String, price: Long) =
    transaction { conn ->
        conn.prepareStatement(
            """
            INSERT INTO products (product_id, device_id, sku, name, unit_price, cost_price, tax_rate_bp,
                                  low_stock_threshold, is_active, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 0, 0, 0, TRUE, now(), now())
            """.trimIndent(),
        ).use { st ->
            st.setString(1, productId)
            st.setString(2, deviceId)
            st.setString(3, sku)
            st.setString(4, name)
            st.setLong(5, price)
            st.executeUpdate()
        }
    }

class EventApplierTest {

    private fun waterItem(seed: ServerSeed, quantity: Long, unitPrice: Long) =
        ClientSaleItem(productId = seed.waterId, sku = "WATER-500", name = "Sachet Water", quantity = quantity, unitPrice = unitPrice)

    @Test
    fun `reprices from the server catalog and marks the sale synced`() {
        val db = TestDb.fresh()
        val seed = seedServer(db, waterPrice = 150)
        val applier = EventApplier(db)
        val saleId = newId()

        val result = applier.apply(
            saleCreatedEvent(
                seed,
                saleId = saleId,
                items = listOf(waterItem(seed, quantity = 2, unitPrice = 150)),
                clientTotals = ClientTotals(subtotal = 300, discountTotal = 0, taxTotal = 0, total = 300),
            ),
        )

        assertEquals("applied", result.status)
        assertEquals(300L, result.serverTotal)
        assertEquals(false, result.discrepancy)

        val sale = db.saleRow(saleId)!!
        assertEquals("synced", sale.syncState)
        assertEquals(300L, sale.serverTotal)
        assertEquals(false, sale.discrepancy)
        assertEquals(1L, db.count("sales"))
        assertEquals(1L, db.count("sale_items")) // one line, quantity 2
        assertEquals(-2L, Reports(db).stockOnHand(seed.waterId))
    }

    @Test
    fun `records the sale with the authoritative total when the client disagrees`() {
        val db = TestDb.fresh()
        // The server says GHS 2.00; the till believed GHS 1.50.
        val seed = seedServer(db, waterPrice = 200)
        val applier = EventApplier(db)
        val saleId = newId()

        val result = applier.apply(
            saleCreatedEvent(
                seed,
                saleId = saleId,
                items = listOf(waterItem(seed, quantity = 1, unitPrice = 150)),
                clientTotals = ClientTotals(subtotal = 150, discountTotal = 0, taxTotal = 0, total = 150),
            ),
        )

        assertEquals(200L, result.serverTotal)
        assertEquals(150L, result.clientTotal)
        assertEquals(true, result.discrepancy)

        val sale = db.saleRow(saleId)!!
        assertEquals(150L, sale.total)          // what the till charged, kept for the record
        assertEquals(200L, sale.serverTotal)    // what the server says it should have been
        assertEquals(true, sale.discrepancy)
        assertEquals("disputed", sale.syncState)

        val flagged = Reports(db).priceDiscrepancies()
        assertEquals(1, flagged.size)
        assertEquals(150L, flagged[0].clientTotal)
        assertEquals(200L, flagged[0].serverTotal)
    }

    @Test
    fun `applies a retried event exactly once`() {
        val db = TestDb.fresh()
        val seed = seedServer(db)
        val applier = EventApplier(db)
        val event = saleCreatedEvent(
            seed,
            items = listOf(waterItem(seed, quantity = 1, unitPrice = 150)),
            clientTotals = ClientTotals(150, 0, 0, 150),
        )

        val first = applier.apply(event)
        val retry = applier.apply(event)

        assertEquals("applied", first.status)
        assertEquals("duplicate", retry.status)
        assertEquals(1L, db.count("sales"))
        assertEquals(1L, db.count("sale_items"))
        // Stock moved once, not twice.
        assertEquals(-1L, Reports(db).stockOnHand(seed.waterId))
    }

    @Test
    fun `lets two offline devices sell the last item, flagging negative stock`() {
        val db = TestDb.fresh()
        val seed = seedServer(db)
        val applier = EventApplier(db)
        val reports = Reports(db)

        // Exactly one unit exists in the world.
        applier.apply(goodsReceivedEvent(seed, productId = seed.waterId, quantity = 1))
        assertEquals(1L, reports.stockOnHand(seed.waterId))

        val saleA = saleCreatedEvent(
            seed,
            deviceId = seed.deviceId,
            items = listOf(waterItem(seed, quantity = 1, unitPrice = 150)),
            clientTotals = ClientTotals(150, 0, 0, 150),
        )
        val saleB = saleCreatedEvent(
            seed,
            deviceId = seed.otherDeviceId,
            items = listOf(waterItem(seed, quantity = 1, unitPrice = 150)),
            clientTotals = ClientTotals(150, 0, 0, 150),
        )

        val results = applier.applyBatch(listOf(saleA, saleB))

        assertTrue(results.all { it.status == "applied" })
        // Neither sale was rejected for insufficient stock.
        assertEquals(2L, db.count("sales"))
        assertEquals(-1L, reports.stockOnHand(seed.waterId))
        assertTrue(reports.stockReviewQueue().isNotEmpty(), "negative stock must be flagged for review")
    }

    @Test
    fun `refuses an event for an unknown product without marking it processed`() {
        val db = TestDb.fresh()
        val seed = seedServer(db)
        val applier = EventApplier(db)
        val unknownProductId = newId()

        val event = saleCreatedEvent(
            seed,
            items = listOf(ClientSaleItem(productId = unknownProductId, quantity = 1, unitPrice = 100)),
            clientTotals = ClientTotals(100, 0, 0, 100),
        )

        assertFailsWith<UnknownProductException> { applier.apply(event) }

        // The whole transaction rolled back: nothing applied, nothing claimed.
        assertEquals(0L, db.processedCount(event.eventId))
        assertEquals(0L, db.count("sales"))
        assertEquals(0L, db.count("stock_movements"))

        // Once the catalog syncs, the very same event applies cleanly.
        db.insertProduct(unknownProductId, seed.deviceId, "NEW-SKU", "New Product", 100)
        val retried = applier.apply(event)
        assertEquals("applied", retried.status)
        assertEquals(1L, db.count("sales"))
        assertEquals(1L, db.processedCount(event.eventId))
    }

    @Test
    fun `applies a batch in per-device sequence order`() {
        val db = TestDb.fresh()
        val seed = seedServer(db)
        val applier = EventApplier(db)

        // Receipt numbers are unique per device, so a device's two sales must differ.
        val first = saleCreatedEvent(
            seed,
            receiptNumber = "TILL0001-20261009-0001",
            items = listOf(waterItem(seed, quantity = 1, unitPrice = 150)),
            clientTotals = ClientTotals(150, 0, 0, 150),
            seq = 1,
        )
        val second = saleCreatedEvent(
            seed,
            receiptNumber = "TILL0001-20261009-0002",
            items = listOf(waterItem(seed, quantity = 4, unitPrice = 150)),
            clientTotals = ClientTotals(600, 0, 0, 600),
            seq = 2,
        )

        val results = applier.applyBatch(listOf(second, first)) // deliberately shuffled

        assertEquals(listOf("applied", "applied"), results.map { it.status })
        assertEquals(5L, -(Reports(db).stockOnHand(seed.waterId)))
    }

    @Test
    fun `applies tax from the server catalog rather than the client figure`() {
        val db = TestDb.fresh()
        // MILO carries 12.5% VAT server-side. The client under-declares it as 0.
        val seed = seedServer(db, miloPrice = 4500, miloTaxBp = 1250)
        val applier = EventApplier(db)

        val result = applier.apply(
            saleCreatedEvent(
                seed,
                items = listOf(
                    ClientSaleItem(
                        productId = seed.miloId,
                        sku = "MILO-400",
                        quantity = 1,
                        unitPrice = 4500,
                        taxRateBp = 0,
                        tax = 0,
                    ),
                ),
                clientTotals = ClientTotals(subtotal = 4500, discountTotal = 0, taxTotal = 0, total = 4500),
            ),
        )

        // 12.5% of 4500 = 562.5 -> 563 rounded half away from zero.
        assertEquals(5063L, result.serverTotal)
        assertEquals(true, result.discrepancy)
    }

    // ----------------------------------------------------- catalog/user events

    @Test
    fun `applies a catalog edit into the authoritative product table`() {
        val db = TestDb.fresh()
        val seed = seedServer(db)
        val applier = EventApplier(db)
        val productId = newId()

        val event = productUpsertedEvent(
            seed,
            productId = productId,
            sku = "TILLEY-TP1",
            unitPrice = 250,
            deviceId = seed.otherDeviceId,
        )
        val result = applier.apply(event)

        assertEquals("applied", result.status)
        db.read { conn ->
            conn.prepareStatement(
                "SELECT device_id, sku, name, unit_price, cost_price, tax_rate_bp, low_stock_threshold, " +
                    "is_active FROM products WHERE product_id = ?",
            ).use { st ->
                st.setString(1, productId)
                st.executeQuery().use { rs ->
                    assertTrue(rs.next(), "the product row should have been written")
                    assertEquals(seed.otherDeviceId, rs.getString("device_id"))
                    assertEquals("TILLEY-TP1", rs.getString("sku"))
                    assertEquals(250L, rs.getLong("unit_price"))
                    assertEquals("TILLEY-TP1", rs.getString("name")) // name falls back to sku
                    assertTrue(rs.getBoolean("is_active"))
                }
            }
        }

        // A re-delivery recognises itself instead of racing the catalog.
        assertEquals("duplicate", applier.apply(event).status)
        assertEquals(1L, db.count("products WHERE product_id = '$productId'"))
    }

    @Test
    fun `applies a catalog edit over an existing product and a later one lands after`() {
        val db = TestDb.fresh()
        val seed = seedServer(db)
        val applier = EventApplier(db)

        applier.apply(
            productUpsertedEvent(seed, productId = seed.waterId, sku = "WATER-500", unitPrice = 150),
        )
        val edit = applier.apply(
            productUpsertedEvent(
                seed,
                productId = seed.waterId,
                sku = "WATER-500",
                unitPrice = 300,
                deviceId = seed.otherDeviceId,
                seq = 2,
            ),
        )
        assertEquals("applied", edit.status)
        db.read { conn ->
            conn.prepareStatement("SELECT unit_price FROM products WHERE product_id = ?").use { st ->
                st.setString(1, seed.waterId)
                st.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(300L, rs.getLong("unit_price"))
                }
            }
        }
    }

    @Test
    fun `refuses a catalog edit with an invalid price without marking it processed`() {
        val db = TestDb.fresh()
        val seed = seedServer(db)
        val applier = EventApplier(db)
        val productId = newId()
        val event = productUpsertedEvent(seed, productId = productId, sku = "BAD-P", unitPrice = -1)

        assertFailsWith<InvalidEventPayloadException> { applier.apply(event) }

        // Rolled back: nothing claimed, nothing written.
        assertEquals(0L, db.processedCount(event.eventId))
        assertEquals(0L, db.count("products WHERE product_id = '$productId'"))
    }

    @Test
    fun `stores a relayed user only when the secrets are hashed and the row is store-global`() {
        val db = TestDb.fresh()
        val seed = seedServer(db)
        val applier = EventApplier(db)
        val userId = newId()

        val ok = userUpsertedEvent(
            seed,
            userId = userId,
            fullName = "Akosua Mensah",
            role = "cashier",
            pinHash = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c2FsdA==\$aGFzaA==",
        )
        assertEquals("applied", applier.apply(ok).status)
        db.read { conn ->
            conn.prepareStatement("SELECT device_id, pin_hash, role FROM users WHERE user_id = ?").use { st ->
                st.setString(1, userId)
                st.executeQuery().use { rs ->
                    assertTrue(rs.next(), "the user row should have been written")
                    assertTrue(rs.getString("device_id") == null, "relayed users are store-global")
                    assertTrue(rs.getString("pin_hash")!!.startsWith("\$argon2"))
                    assertEquals("cashier", rs.getString("role"))
                }
            }
        }
        val usersBefore = db.count("users")

        // A rogue till shipping the actual PIN gets a rollback, not a user row.
        val plaintext = userUpsertedEvent(
            seed,
            userId = newId(),
            fullName = "Rogue Cashier",
            role = "cashier",
            pinHash = "1234",
            seq = 2,
        )
        assertFailsWith<InvalidEventPayloadException> { applier.apply(plaintext) }
        assertEquals(0L, db.processedCount(plaintext.eventId))
        assertEquals(usersBefore, db.count("users"))

        // The same refusal crosses the store-global rule and both secret fields.
        val bound = userUpsertedEvent(
            seed,
            userId = newId(),
            fullName = "Bound Cashier",
            role = "cashier",
            seq = 3,
            payloadDeviceId = seed.otherDeviceId,
        )
        assertFailsWith<InvalidEventPayloadException> { applier.apply(bound) }
        assertEquals(0L, db.processedCount(bound.eventId))
        assertEquals(usersBefore, db.count("users"))
    }
}
