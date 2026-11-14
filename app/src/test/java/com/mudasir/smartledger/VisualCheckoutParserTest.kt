package com.mudasir.smartledger

import com.mudasir.smartledger.capture.VisualCheckoutParser
import com.mudasir.smartledger.capture.VisualText
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisualCheckoutParserTest {
    @Test fun `jd fallback requires matching digits and aligned prominent price with checkout controls`() {
        val fullPrice = VisualText("49706", 350, 130, 630, 160)
        val latinPrice = VisualText("497.06", 356, 130, 632, 164)
        val controls = listOf(line("微信支付", 100, 590), line("确认付款", 700, 900))
        fun inspect(full: List<VisualText>, latin: List<VisualText>) =
            VisualCheckoutParser.inspectJdCashier(full, 1000, 1000, latinScreenLines = latin).amount
        assertEquals(BigDecimal("497.06"), inspect(controls + fullPrice, listOf(latinPrice)))
        assertNull(inspect(controls + fullPrice.copy(text = "49606"), listOf(latinPrice)))
        assertNull(inspect(controls + fullPrice.copy(left = 50, right = 330), listOf(latinPrice)))
        assertNull(inspect(controls.drop(1) + fullPrice, listOf(latinPrice)))
        assertNull(inspect(controls + fullPrice, listOf(latinPrice.copy(bottom = 140))))
        assertNull(inspect(controls + fullPrice + fullPrice.copy(text = "49606"), listOf(latinPrice, latinPrice.copy(text = "496.06"))))
        assertNull(inspect(controls + fullPrice.copy(text = "订单号49706"), listOf(latinPrice)))
    }
    @Test fun `jd cashier requires unique centered currency and explicit payment controls`() {
        val price = line("¥ 497．00", 450, 140)
        val wechat = line("微信支付", 100, 590)
        val confirm = line("确认付款", 700, 900)
        fun jd(vararg lines: VisualText) = VisualCheckoutParser.amountForJdCashier(lines.toList(), 1000, 1000)
        assertEquals(BigDecimal("497.00"), jd(price, wechat, confirm))
        assertNull(jd(price, confirm))
        assertNull(jd(price, wechat, line("查看优惠", 700, 900)))
        assertNull(jd(price, wechat, confirm, line("￥496.00", 450, 170)))
        assertNull(jd(line("订单号 12345678", 450, 140), wechat, confirm))
        assertNull(jd(line("¥497.00", 0, 140), wechat, confirm))
        assertNull(jd(price, wechat, confirm, line("付款成功")))
        val noPrice = listOf(wechat, confirm)
        assertEquals(BigDecimal("497.00"), VisualCheckoutParser.inspectJdCashier(noPrice, 1000, 1000, "¥ 497 ． 0 0").amount)
        assertNull(VisualCheckoutParser.inspectJdCashier(noPrice, 1000, 1000, "497.00").amount)
        assertNull(VisualCheckoutParser.inspectJdCashier(noPrice, 1000, 1000, "¥497.00 ¥496.00").amount)
        assertEquals(BigDecimal("497.00"), VisualCheckoutParser.inspectJdCashier(noPrice, 1000, 1000, null, "¥497.00").amount)
        assertNull(VisualCheckoutParser.inspectJdCashier(noPrice, 1000, 1000, "¥497.00", "¥496.00").amount)
        assertNull(VisualCheckoutParser.inspectJdCashier(listOf(confirm), 1000, 1000, "¥497.00").amount)
    }
    @Test fun `taobao sku selection alone cannot qualify as a final order`() {
        val skuPrice = line("应付 ¥501", 0, 800)
        val buy = line("立即付款", 700, 850)
        assertNull(VisualCheckoutParser.amountForTaobaoSku(listOf(skuPrice, buy), 1000, 1000))
        assertNull(VisualCheckoutParser.amountForTaobaoSku(listOf(line("确认订单"), buy, line("商品价格 ¥501")), 1000, 1000))
        assertEquals(BigDecimal("501"), VisualCheckoutParser.amountForTaobaoSku(listOf(line("确认订单"), skuPrice, buy), 1000, 1000))
    }
    @Test fun `jd payment button amount is preferred over add-on prices and zero down payment`() {
        val pay = line("立即支付¥137.42", 600, 900)
        assertEquals(BigDecimal("137.42"), parse(pay, line("¥3.99专享价", 100, 700),
            line("先享后付0元下单", 0, 900), line("￥137.42补贴价", 100, 600)))
        assertNull(parse(line("立即支付¥137.42", 0, 100), line("¥3.99", 100, 700)))
        assertNull(parse(pay, line("立即支付¥20.00", 100, 900)))
        assertNull(parse(line("先享后付0元下单", 0, 900), line("¥3.99专享价", 100, 700)))
        assertEquals(BigDecimal("137.42"), parse(line("立即支付", 600, 900), line("¥137.42", 710, 900), line("¥3.99", 100, 700)))
        assertNull(parse(line("立即支付", 100, 900), line("¥3.99", 700, 700)))
    }
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
