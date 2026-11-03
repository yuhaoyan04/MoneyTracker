package com.mudasir.smartledger

import com.mudasir.smartledger.capture.CheckoutNode
import com.mudasir.smartledger.capture.CheckoutPageParser
import com.mudasir.smartledger.capture.CheckoutResult.Status
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckoutPageParserTest {
    private fun n(text: String, parent: Int = 1, x: Int = 0, y: Int = 0) = CheckoutNode(text, parent, x, y, x + 120, y + 40)
    private val threshold = BigDecimal("500")
    private val pkg = "com.taobao.taobao"

    @Test fun `reminder gate rejects missing values and equal amounts`() {
        assertFalse(CheckoutPageParser.exceedsThreshold(Double.NaN, 500f))
        assertFalse(CheckoutPageParser.exceedsThreshold(Double.POSITIVE_INFINITY, 500f))
        assertFalse(CheckoutPageParser.exceedsThreshold(500.0, 500f))
        assertFalse(CheckoutPageParser.exceedsThreshold(10.03, 10.03f))
        assertTrue(CheckoutPageParser.exceedsThreshold(10.04, 10.03f))
        assertFalse(CheckoutPageParser.exceedsThreshold(501.0, Float.NaN))
        assertTrue(CheckoutPageParser.exceedsThreshold(500.01, 500f))
    }

    @Test fun `requires checkout action and a labelled payment amount`() {
        assertEquals(Status.QUALIFIED, CheckoutPageParser.analyze(pkg, listOf(n("确认订单"), n("实付款 ¥501.00")), threshold).status)
        assertEquals(Status.NOT_CHECKOUT, CheckoutPageParser.analyze(pkg, listOf(n("实付款 ¥501.00")), threshold).status)
        assertEquals(Status.NO_AMOUNT, CheckoutPageParser.analyze(pkg, listOf(n("立即付款"), n("优惠 ¥20"), n("原价 ¥999")), threshold).status)
        assertEquals(Status.NOT_CHECKOUT, CheckoutPageParser.analyze("com.example.ad", listOf(n("确认订单"), n("实付款 ¥501")), threshold).status)
        assertEquals(Status.NO_ACCESSIBLE_TEXT, CheckoutPageParser.analyze(pkg, emptyList(), threshold).status)
    }

    @Test fun `amount must be strictly greater and outcome pages are excluded`() {
        assertEquals(Status.BELOW_THRESHOLD, CheckoutPageParser.analyze(pkg, listOf(n("提交订单"), n("合计 ¥500.00")), threshold).status)
        assertEquals(Status.BELOW_THRESHOLD, CheckoutPageParser.analyze(pkg, listOf(n("提交订单"), n("合计 ¥499.99")), threshold).status)
        assertEquals(Status.QUALIFIED, CheckoutPageParser.analyze(pkg, listOf(n("提交订单"), n("合计 ¥500.01")), threshold).status)
        assertEquals(Status.NOT_CHECKOUT, CheckoutPageParser.analyze(pkg, listOf(n("支付成功"), n("确认订单"), n("实付款 ¥600")), threshold).status)
    }

    @Test fun `only nearby siblings supply a split label and conflicting totals are rejected`() {
        val split = listOf(n("确认付款"), n("实付款", 2, 0), n("¥", 2, 130), n("1,234.56", 2, 150))
        assertEquals(Status.QUALIFIED, CheckoutPageParser.analyze(pkg, split, threshold).status)
        assertEquals(Status.NO_AMOUNT, CheckoutPageParser.analyze(pkg, listOf(n("确认付款"), n("实付款", 2), n("¥999", 3)), threshold).status)
        assertEquals(Status.AMBIGUOUS, CheckoutPageParser.analyze(pkg, listOf(n("确认付款"), n("实付款 ¥600"), n("合计 ¥900")), threshold).status)
        assertEquals(Status.QUALIFIED, CheckoutPageParser.analyze("com.MobileTicket", listOf(n("提交订单"), n("应付金额 800元")), threshold).status)
    }
}
