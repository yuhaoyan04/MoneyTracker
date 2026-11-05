package com.mudasir.smartledger

import com.mudasir.smartledger.capture.VisualCheckoutParser
import com.mudasir.smartledger.capture.VisualText
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisualCheckoutParserTest {
    private fun line(s: String, x: Int = 0, y: Int = 800) = VisualText(s, x, y, x + 100, y + 30)
    private val action = line("继续付款", 700, 850)
    private fun parse(vararg lines: VisualText) = VisualCheckoutParser.amount(lines.toList(), 1000, 1000)

    @Test fun `only explicit payable candidates are considered`() {
        assertEquals(BigDecimal("501.00"), parse(action, line("应付 ¥501.00"), line("优惠 ¥3"), line("订单号 123456789123")))
        assertNull(parse(action, line("合计 ¥800"), line("优惠 ¥3")))
        assertNull(parse(line("应付 ¥501")))
        assertNull(parse(action, line("应付 ¥123456789123")))
    }

    @Test fun `chooses smallest labelled candidate and supports nearby amount`() {
        assertEquals(BigDecimal("3.50"), parse(action, line("应付 ¥501"), line("实付款 ¥3.50")))
        assertEquals(BigDecimal("501"), parse(action, line("应付", 0), line("¥501", 110)))
        assertNull(parse(action, line("应付", 0), line("¥501", 700)))
    }

    @Test fun `results and early page buttons are not active checkouts`() {
        assertNull(parse(action, line("应付 ¥501"), line("支付成功")))
        assertNull(parse(line("继续付款", 0, 50), line("应付 ¥501")))
        assertNull(parse(action, line("应付 ¥501"), line("全部订单")))
        assertNull(parse(action, line("应付 ¥501"), line("待付款"), line("待发货")))
    }
}
