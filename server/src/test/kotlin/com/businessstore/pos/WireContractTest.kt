package com.businessstore.pos

import com.businessstore.pos.db.Database
import com.businessstore.pos.domain.PosJson
import com.businessstore.pos.domain.SaleCreatedPayload
import com.businessstore.pos.domain.SyncEventEnvelope
import com.businessstore.pos.reports.Reports
import com.businessstore.pos.sync.EventApplier
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.jupiter.api.Test
import java.io.File
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
data class ContractProduct(
    val productId: String,
    val sku: String,
    val name: String,
    val unitPrice: Long,
    val costPrice: Long = 0,
    val taxRateBp: Long = 0,
    val lowStockThreshold: Long = 0,
)

@Serializable
data class ContractSeed(
    val storeId: String,
    val deviceId: String,
    val cashierId: String,
    val managerId: String,
    val supplierId: String,
    val products: List<ContractProduct>,
)

@Serializable
data class ContractExpectations(
    val productId: String,
    val stockOnHandAfter: Long,
    val saleTotal: Long,
    val saleTaxTotal: Long,
    val receiptNumber: String,
)

@Serializable
data class ContractFile(
    val generatedAt: String,
    val note: String? = null,
    val seed: ContractSeed,
    val expectations: ContractExpectations,
    val events: List<SyncEventEnvelope>,
)

/**
 * The cross-language guard.
 *
 * `contract/events.json` is produced by the REAL TypeScript client domain code
 * (`npm run contract:emit`: goods received, shift open, a manual movement, a
 * discounted checkout, a confirmed payment). This test seeds PostgreSQL from the
 * fixture's own ids, applies those exact envelopes with the real `EventApplier`,
 * and asserts the server reached the totals the device expects.
 *
 * If either side renames, retypes or drops a field, this fails. It is the only
 * thing standing between two hand-written implementations of one protocol.
 */
class WireContractTest {

    private fun contractFile(): File {
        val candidates = listOf(
            File("../contract/events.json"),
            File("contract/events.json"),
            File(System.getProperty("user.dir")).resolve("../contract/events.json"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "contract/events.json not found. Run `npm run contract:emit` in the project root first. " +
                    "Looked in: ${candidates.joinToString { it.absolutePath }}",
            )
    }

    private fun loadContract(): ContractFile =
        PosJson.decodeFromString(ContractFile.serializer(), contractFile().readText())

    private fun seedFromContract(db: Database, seed: ContractSeed) {
        db.transaction { conn ->
            val now = OffsetDateTime.now()
            conn.prepareStatement(
                """
                INSERT INTO devices (device_id, store_id, name, model, registered_at, last_seen_at,
                                     token_expires_at, is_active)
                VALUES (?, ?, 'TILL-1', 'tablet', ?, NULL, NULL, TRUE)
                """.trimIndent(),
            ).use { st ->
                st.setString(1, seed.deviceId)
                st.setString(2, seed.storeId)
                st.setObject(3, now)
                st.executeUpdate()
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
                    st.setString(2, "Contract $role")
                    st.setString(3, role)
                    st.setObject(4, now)
                    st.setObject(5, now)
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
                st.setObject(3, now)
                st.setObject(4, now)
                st.executeUpdate()
            }

            for (product in seed.products) {
                conn.prepareStatement(
                    """
                    INSERT INTO products (product_id, device_id, sku, name, unit_price, cost_price, tax_rate_bp,
                                          low_stock_threshold, is_active, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?)
                    """.trimIndent(),
                ).use { st ->
                    st.setString(1, product.productId)
                    st.setString(2, seed.deviceId)
                    st.setString(3, product.sku)
                    st.setString(4, product.name)
                    st.setLong(5, product.unitPrice)
                    st.setLong(6, product.costPrice)
                    st.setLong(7, product.taxRateBp)
                    st.setLong(8, product.lowStockThreshold)
                    st.setObject(9, now)
                    st.setObject(10, now)
                    st.executeUpdate()
                }
            }
        }
    }

    @Test
    fun `the client fixture exists and covers the event types`() {
        val contract = loadContract()
        val types = contract.events.map { it.eventType }.toSet()
        assertEquals(
            setOf("goods_received.created", "shift.opened", "stock.movement", "sale.created", "payment.updated"),
            types,
        )
        assertTrue(contract.seed.products.isNotEmpty())
    }

    @Test
    fun `applying the client's real events reaches the totals the device expects`() {
        val contract = loadContract()
        val db = TestDb.fresh()
        seedFromContract(db, contract.seed)

        val results = EventApplier(db).applyBatch(contract.events)

        assertTrue(results.all { it.status == "applied" }, "expected every contract event to apply: $results")

        // The server repriced the discounted sale and agreed with the till.
        val saleEvent = contract.events.first { it.eventType == "sale.created" }
        val payload = PosJson.decodeFromJsonElement<SaleCreatedPayload>(saleEvent.payload)
        assertEquals(contract.expectations.saleTotal, payload.clientTotals.total)
        assertEquals(contract.expectations.saleTaxTotal, payload.clientTotals.taxTotal)
        assertEquals(contract.expectations.receiptNumber, payload.receiptNumber)

        val saleResult = results.first { it.eventId == saleEvent.eventId }
        assertEquals(payload.clientTotals.total, saleResult.serverTotal)
        assertEquals(false, saleResult.discrepancy)

        db.read { conn ->
            conn.prepareStatement(
                "SELECT total, server_total, tax_total, price_discrepancy, sync_state FROM sales WHERE sale_id = ?",
            ).use { st ->
                st.setString(1, payload.saleId)
                st.executeQuery().use { rs ->
                    assertTrue(rs.next(), "the sale should have been written")
                    assertEquals(contract.expectations.saleTotal, rs.getLong("total"))
                    assertEquals(contract.expectations.saleTotal, rs.getLong("server_total"))
                    assertEquals(contract.expectations.saleTaxTotal, rs.getLong("tax_total"))
                    assertEquals(false, rs.getBoolean("price_discrepancy"))
                    assertEquals("synced", rs.getString("sync_state"))
                }
            }
        }

        // Goods received, then disbursed, then sold: the ledger and the cache agree.
        assertEquals(contract.expectations.stockOnHandAfter, Reports(db).stockOnHand(contract.expectations.productId))

        // The shift the sale referenced was created before the sale that points at it.
        db.read { conn ->
            conn.prepareStatement("SELECT status FROM shifts").use { st ->
                st.executeQuery().use { rs ->
                    assertTrue(rs.next(), "shift.opened should have created the shift")
                    assertEquals("open", rs.getString("status"))
                }
            }
        }
    }
}
