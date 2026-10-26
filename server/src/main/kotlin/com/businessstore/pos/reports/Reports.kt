package com.businessstore.pos.reports

import com.businessstore.pos.db.Database
import com.businessstore.pos.domain.DailySalesDto
import com.businessstore.pos.domain.StockLevelDto
import kotlinx.serialization.Serializable

@Serializable
data class StockReviewDto(
    val movementId: String,
    val deviceId: String,
    val productId: String,
    val quantityDelta: Long,
    val reasonCode: String,
    val occurredAt: String,
)

@Serializable
data class PriceDiscrepancyDto(
    val saleId: String,
    val receiptNumber: String,
    val deviceId: String,
    val clientTotal: Long,
    val serverTotal: Long?,
)

/**
 * Reporting reads derive from the ledger. Nothing is stored as a mutable running
 * total, so a report cannot drift from the transactions that produced it.
 */
class Reports(private val db: Database) {

    /** Sales per day. Refunds and voids reduce the net; they never delete a sale. */
    fun dailySales(fromDay: String, toDay: String, deviceId: String? = null): List<DailySalesDto> =
        db.read { conn ->
            val deviceClause = if (deviceId != null) "AND device_id = ?" else ""
            val sql = """
                SELECT to_char(created_at, 'YYYY-MM-DD') AS day, kind,
                       COUNT(*) AS n, SUM(total) AS total,
                       SUM(discount_total) AS discount, SUM(tax_total) AS tax
                  FROM sales
                 WHERE created_at::date BETWEEN ?::date AND ?::date $deviceClause
                 GROUP BY day, kind
                 ORDER BY day
            """.trimIndent()

            data class Acc(var salesCount: Long, var gross: Long, var discount: Long, var tax: Long)

            val byDay = LinkedHashMap<String, Acc>()
            conn.prepareStatement(sql).use { st ->
                st.setString(1, fromDay)
                st.setString(2, toDay)
                if (deviceId != null) st.setString(3, deviceId)
                st.executeQuery().use { rs ->
                    while (rs.next()) {
                        val day = rs.getString("day")
                        val kind = rs.getString("kind")
                        val acc = byDay.getOrPut(day) { Acc(0, 0, 0, 0) }
                        // The sign encodes whether this row adds to or reverses takings.
                        val sign = if (kind == "sale") 1L else -1L
                        if (kind == "sale") acc.salesCount += rs.getLong("n")
                        acc.gross += sign * rs.getLong("total")
                        acc.discount += sign * rs.getLong("discount")
                        acc.tax += sign * rs.getLong("tax")
                    }
                }
            }
            byDay.map { (day, acc) ->
                DailySalesDto(
                    day = day,
                    salesCount = acc.salesCount,
                    grossTotal = acc.gross,
                    discountTotal = acc.discount,
                    taxTotal = acc.tax,
                )
            }
        }

    fun stockLevels(): List<StockLevelDto> = db.read { conn ->
        val sql = """
            SELECT p.product_id, p.sku, p.name,
                   COALESCE(s.quantity, 0) AS quantity,
                   p.low_stock_threshold
              FROM products p
              LEFT JOIN stock_on_hand s ON s.product_id = p.product_id
             WHERE p.is_active = TRUE
             ORDER BY p.name
        """.trimIndent()
        conn.prepareStatement(sql).use { st ->
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val onHand = rs.getLong("quantity")
                        val threshold = rs.getLong("low_stock_threshold")
                        add(
                            StockLevelDto(
                                productId = rs.getString("product_id"),
                                sku = rs.getString("sku"),
                                name = rs.getString("name"),
                                onHand = onHand,
                                threshold = threshold,
                                low = onHand <= threshold,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun lowStockAlerts(): List<StockLevelDto> = stockLevels().filter { it.low }

    /** Movements that pushed stock negative and need a human to confirm. */
    fun stockReviewQueue(): List<StockReviewDto> = db.read { conn ->
        val sql = """
            SELECT movement_id, device_id, product_id, quantity_delta, reason_code, occurred_at
              FROM stock_movements
             WHERE requires_review = TRUE
             ORDER BY occurred_at DESC
        """.trimIndent()
        conn.prepareStatement(sql).use { st ->
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            StockReviewDto(
                                movementId = rs.getString("movement_id"),
                                deviceId = rs.getString("device_id"),
                                productId = rs.getString("product_id"),
                                quantityDelta = rs.getLong("quantity_delta"),
                                reasonCode = rs.getString("reason_code"),
                                occurredAt = rs.getObject("occurred_at").toString(),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Sales the server priced differently from the device, for finance review. */
    fun priceDiscrepancies(): List<PriceDiscrepancyDto> = db.read { conn ->
        val sql = """
            SELECT sale_id, receipt_number, device_id, total, server_total
              FROM sales
             WHERE price_discrepancy = TRUE OR sync_state = 'disputed'
             ORDER BY created_at DESC
        """.trimIndent()
        conn.prepareStatement(sql).use { st ->
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            PriceDiscrepancyDto(
                                saleId = rs.getString("sale_id"),
                                receiptNumber = rs.getString("receipt_number"),
                                deviceId = rs.getString("device_id"),
                                clientTotal = rs.getLong("total"),
                                serverTotal = rs.getLong("server_total").takeIf { !rs.wasNull() },
                            ),
                        )
                    }
                }
            }
        }
    }

    fun stockOnHand(productId: String): Long = db.read { conn ->
        conn.prepareStatement("SELECT quantity FROM stock_on_hand WHERE product_id = ?").use { st ->
            st.setString(1, productId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    fun saleCount(): Long = db.read { conn ->
        conn.prepareStatement("SELECT COUNT(*) FROM sales").use { st ->
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }
}
