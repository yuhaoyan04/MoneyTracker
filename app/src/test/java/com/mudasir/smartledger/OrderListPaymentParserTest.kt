package com.mudasir.smartledger

import com.mudasir.smartledger.capture.CheckoutNode
import com.mudasir.smartledger.capture.OrderListPaymentParser
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class OrderListPaymentParserTest {
    private fun n(text: String, x: Int, y: Int, parent: Int = 1) = CheckoutNode(text, parent, x, y, x + 100, y + 30)
    private fun list() = listOf(n("待付款", 100, 50), n("待发货", 300, 50),
        n("待付款", 800, 100), n("应付", 600, 300, 10), n("¥12.00", 710, 300, 10), n("继续付款", 800, 400),
        n("待付款", 800, 500), n("应付", 600, 700, 20), n("¥99.00", 710, 700, 20), n("继续付款", 800, 800))

    @Test fun matchesClickedButtonToOnlyItsOwnOrder() {
        val candidates = OrderListPaymentParser.candidates(list(), 1000, 2000)
        assertEquals(2, candidates.size)
        assertEquals(BigDecimal("12"), OrderListPaymentParser.match(candidates, 800, 400, 900, 430)?.amount)
        assertEquals(BigDecimal("99"), OrderListPaymentParser.match(candidates, 800, 800, 900, 830)?.amount)
        assertNull(OrderListPaymentParser.match(candidates, 100, 400, 200, 430))
    }

    @Test fun rejectsConflictingAmountsAndMissingOrderBoundary() {
        val ambiguous = list() + n("实付款 ¥8.00", 500, 350)
        val candidates = OrderListPaymentParser.candidates(ambiguous, 1000, 2000)
        assertNull(OrderListPaymentParser.match(candidates, 800, 400, 900, 430))
        val missingStatus = list().filterNot { it.text == "待付款" && it.top == 500 }
        assertNull(OrderListPaymentParser.match(OrderListPaymentParser.candidates(missingStatus, 1000, 2000), 800, 800, 900, 830))
    }
}
