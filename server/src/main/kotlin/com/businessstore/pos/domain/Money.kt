package com.businessstore.pos.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Money in integer minor units (1 GHS = 100 pesewas).
 *
 * This is a deliberate mirror of the TypeScript `src/domain/money.ts`. Both sides
 * must agree to the pesewa, so the rules are identical: integers only, half-away-
 * from-zero rounding, and documented behaviour is locked down by tests on BOTH
 * sides (`tests/money.test.ts` and `MoneyTest.kt`).
 */
typealias Minor = Long

class MoneyException(message: String) : IllegalArgumentException(message)

private const val RATE_SCALE = 10000L

fun roundHalfAwayFromZero(value: Double): Long =
    if (value >= 0) Math.round(value) else -Math.round(-value)

fun add(vararg amounts: Minor): Minor = amounts.sum()

fun sub(a: Minor, b: Minor): Minor = a - b

fun sum(amounts: List<Minor>): Minor = amounts.sum()

fun negate(amount: Minor): Minor = -amount

/** unit price x quantity, exact because both are integers. */
fun lineGross(unitPrice: Minor, quantity: Long): Minor {
    if (quantity <= 0) throw MoneyException("quantity must be positive, got $quantity")
    return unitPrice * quantity
}

/**
 * Apply a basis-point rate (10000 bp = 100%) rounded half away from zero.
 *
 * Uses BigDecimal rather than Double so a large amount cannot lose precision to
 * floating point — the exact bug this whole module exists to avoid.
 */
fun applyRate(amount: Minor, rateBp: Long): Minor =
    BigDecimal(amount)
        .multiply(BigDecimal(rateBp))
        .divide(BigDecimal(RATE_SCALE), 0, RoundingMode.HALF_UP)
        .longValueExact()

data class LinePrice(
    val gross: Minor,
    val discount: Minor,
    val net: Minor,
    val tax: Minor,
    val total: Minor,
)

/** Line pricing: discount is absolute, and tax is charged on the post-discount net. */
fun priceLine(
    unitPrice: Minor,
    quantity: Long,
    discount: Minor = 0,
    taxRateBp: Long = 0,
): LinePrice {
    val gross = lineGross(unitPrice, quantity)
    if (discount < 0) throw MoneyException("discount cannot be negative")
    if (discount > gross) throw MoneyException("discount cannot exceed the line gross amount")
    val net = gross - discount
    val tax = applyRate(net, taxRateBp)
    return LinePrice(gross = gross, discount = discount, net = net, tax = tax, total = net + tax)
}

fun changeDue(tendered: Minor, total: Minor): Minor = tendered - total

/** Human-readable GHS string for receipts and reports only — never for storage. */
fun formatGhs(amount: Minor, currency: String = "GHS"): String {
    val sign = if (amount < 0) "-" else ""
    val abs = Math.abs(amount)
    val units = abs / 100
    val minor = (abs % 100).toString().padStart(2, '0')
    val grouped = units.toString().reversed().chunked(3).joinToString(",").reversed()
    return "$sign$currency $grouped.$minor"
}
