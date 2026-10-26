package com.businessstore.pos

import com.businessstore.pos.domain.MoneyException
import com.businessstore.pos.domain.add
import com.businessstore.pos.domain.applyRate
import com.businessstore.pos.domain.changeDue
import com.businessstore.pos.domain.formatGhs
import com.businessstore.pos.domain.lineGross
import com.businessstore.pos.domain.priceLine
import com.businessstore.pos.domain.roundHalfAwayFromZero
import com.businessstore.pos.domain.sum
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The SAME assertions as the TypeScript suite (`tests/money.test.ts`).
 *
 * The till and the server both price lines, so if these two implementations ever
 * disagree by a pesewa, one of these two suites fails. That parity is the point:
 * duplicated arithmetic across languages is a real risk, and this is the guard.
 */
class MoneyTest {

    @Test
    fun `adds and sums integer minor units exactly`() {
        assertEquals(5450L, add(150, 800, 4500))
        assertEquals(6L, sum(listOf(1, 2, 3)))
        assertEquals(30L, add(10, 20))
    }

    @Test
    fun `multiplies price by quantity without drift`() {
        assertEquals(450L, lineGross(150, 3))
        assertEquals(31_500L, lineGross(4500, 7))
    }

    @Test
    fun `applies basis point rates with half away from zero rounding`() {
        assertEquals(100L, applyRate(800, 1250))
        assertEquals(1L, roundHalfAwayFromZero(0.5))
        assertEquals(2L, roundHalfAwayFromZero(1.5))
        assertEquals(-1L, roundHalfAwayFromZero(-0.5))
        assertEquals(0L, applyRate(150, 0))
    }

    @Test
    fun `prices a line with discount then tax on the discounted net`() {
        val line = priceLine(unitPrice = 800, quantity = 2, discount = 160, taxRateBp = 1250)
        assertEquals(1600L, line.gross)
        assertEquals(160L, line.discount)
        assertEquals(1440L, line.net)
        assertEquals(180L, line.tax)
        assertEquals(1620L, line.total)
    }

    @Test
    fun `computes change for a cash tender`() {
        assertEquals(380L, changeDue(tendered = 2000, total = 1620))
    }

    @Test
    fun `refuses a discount larger than the line gross`() {
        assertFailsWith<MoneyException> { priceLine(unitPrice = 100, quantity = 1, discount = 101) }
    }

    @Test
    fun `refuses a non-positive quantity`() {
        assertFailsWith<MoneyException> { lineGross(100, 0) }
    }

    @Test
    fun `rounds large amounts without floating point loss`() {
        // 12.5% of GHS 1,000,000.00 is exactly GHS 125,000.00; a Double would be
        // at the edge of its exact-integer range here.
        assertEquals(12_500_000L, applyRate(100_000_000, 1250))
    }

    @Test
    fun `formats GHS for receipts without depending on locale data`() {
        assertEquals("GHS 16.20", formatGhs(1620))
        assertEquals("GHS 1.50", formatGhs(150))
        assertEquals("GHS 1,234,567.89", formatGhs(123_456_789))
        assertEquals("-GHS 2.50", formatGhs(-250))
    }
}
