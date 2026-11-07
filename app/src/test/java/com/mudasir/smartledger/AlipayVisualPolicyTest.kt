package com.mudasir.smartledger

import com.mudasir.smartledger.capture.AlipayVisualPolicy
import com.mudasir.smartledger.capture.JdAlipayEntryTracker
import com.mudasir.smartledger.capture.ShoppingAlipayEntryTracker
import com.mudasir.smartledger.capture.StableAlipayAmount
import com.mudasir.smartledger.capture.VisualText
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class AlipayVisualPolicyTest {
    @Test fun `other shopping app must click before specific alipay cashier and handoff is one shot`() {
        val target = "com.alipay.android.msp.ui.views.MspContainerActivity"
        val apps = listOf("com.taobao.taobao", "com.xunmeng.pinduoduo", "com.sankuai.meituan", "com.MobileTicket")
        apps.forEach { pkg ->
            val t = ShoppingAlipayEntryTracker()
            assertNull(t.onWindow(pkg, "$pkg.CheckoutActivity", 1000))
            assertFalse(t.onClick(pkg, listOf("支付优惠"), 1500))
            assertNull(t.onWindow(AlipayVisualPolicy.ALIPAY, target, 1600))
            t.onWindow(pkg, "$pkg.CheckoutActivity", 2000)
            assertTrue(t.onClick(pkg, listOf("确认付款"), 2500))
            assertEquals(pkg, t.onWindow(AlipayVisualPolicy.ALIPAY, target, 3000))
            assertNull(t.onWindow(AlipayVisualPolicy.ALIPAY, target, 3100))
        }
    }

    @Test fun `other shopping handoff expires and is cleared when leaving the source`() {
        val t = ShoppingAlipayEntryTracker()
        val source = "com.xunmeng.pinduoduo"
        val target = "com.alipay.android.msp.ui.views.MspContainerActivity"
        t.onWindow(source, "$source.CheckoutActivity", 1000)
        assertTrue(t.onClick(source, listOf("立即支付"), 1500))
        assertNull(t.onWindow("com.example.other", "HomeActivity", 2000))
        assertNull(t.onWindow(AlipayVisualPolicy.ALIPAY, target, 2500))
        t.onWindow(source, "$source.CheckoutActivity", 3000)
        assertTrue(t.onClick(source, listOf("立即支付"), 3500))
        assertNull(t.onWindow(AlipayVisualPolicy.ALIPAY, target, 18501))
        t.onWindow(source, "$source.CheckoutActivity", 20000)
        assertFalse(t.onClick(source, listOf("立即支付"), 80001))
    }
    @Test fun `common checkout actions require exact labels`() {
        listOf("提交并支付", "去付款", "去支付").forEach {
            assertTrue(AlipayVisualPolicy.isPayAction(listOf(it)))
        }
        listOf("先享后付0元下单", "支付优惠", "去付款可享优惠", "提交订单").forEach {
            assertFalse(AlipayVisualPolicy.isPayAction(listOf(it)))
        }
    }
    @Test fun `amount must be seen twice consecutively`() {
        val state = StableAlipayAmount()
        assertNull(state.observe(BigDecimal("123.45")))
        assertNull(state.observe(BigDecimal("122.45")))
        assertEquals(BigDecimal("122.45"), state.observe(BigDecimal("122.45")))
        assertNull(state.observe(null))
        assertNull(state.observe(BigDecimal("122.45")))
    }
    @Test fun `only explicit JD cashier payment followed by alipay cashier arms scan`() {
        val t = JdAlipayEntryTracker()
        val jd = AlipayVisualPolicy.JD; val alipay = AlipayVisualPolicy.ALIPAY
        val jdCashier = "com.jd.cashier.app.cashier.tn.view.CashierTnPayPopActivity"
        val target = "com.alipay.android.msp.ui.views.MspContainerActivity"
        assertFalse(t.onWindow(alipay, target, 100))
        t.onWindow(jd, jdCashier, 200); t.onClick(jd, listOf("确认付款"), 300)
        assertTrue(t.onWindow(alipay, target, 1000))
        assertFalse(t.onWindow(alipay, target, 1200))
        t.onWindow(jd, jdCashier, 2000); t.onClick(jd, listOf("确认付款"), 2100)
        assertFalse(t.onWindow(alipay, target, 18000))
        t.onWindow(jd, jdCashier, 20000); t.onClick(jd, listOf("支付优惠"), 21000)
        assertFalse(t.onWindow(alipay, target, 22000))
    }
    @Test fun `pending cashier needs payment and password affordances and rejects actual outcomes`() {
        fun l(s: String, y: Int) = VisualText(s, 0, y, 100, y + 20)
        assertTrue(AlipayVisualPolicy.isPendingCashier(listOf(l("使用密码", 200), l("支付成功得20积分", 400), l("付款", 900)), 1000))
        assertFalse(AlipayVisualPolicy.isPendingCashier(listOf(l("使用密码", 200), l("付款", 900), l("支付成功", 400)), 1000))
        assertFalse(AlipayVisualPolicy.isPendingCashier(listOf(l("付款", 900)), 1000))
    }
    @Test fun `cashier entry survives short system transitions but expires and clears for another JD page`() {
        val t = JdAlipayEntryTracker()
        val jd = AlipayVisualPolicy.JD; val alipay = AlipayVisualPolicy.ALIPAY
        val cashier = "com.jd.cashier.app.cashier.tn.view.CashierTnPayPopActivity"
        val target = "com.alipay.android.msp.ui.views.MspContainerActivity"
        t.onWindow(jd, cashier, 1000)
        t.onWindow("com.android.systemui", "SomeWindow", 2000)
        assertTrue(t.onClick(jd, listOf("立即支付"), 3000))
        assertTrue(t.onWindow(alipay, target, 4000))
        t.onWindow(jd, cashier, 5000)
        assertFalse(t.onClick(jd, listOf("确认付款"), 65001))
        t.onWindow(jd, cashier, 70000)
        t.onWindow(jd, "com.jd.OtherActivity", 71000)
        assertFalse(t.onClick(jd, listOf("确认付款"), 72000))
        t.onWindow(jd, cashier, 80000)
        assertTrue(t.onClick(jd, listOf("确认付款"), 81000))
        t.onWindow("com.example.other", "HomeActivity", 82000)
        assertFalse(t.onWindow(alipay, target, 83000))
    }
    @Test fun `cropped price requires one unambiguous decimal amount`() {
        assertEquals(BigDecimal("123.45"), AlipayVisualPolicy.cropAmount("¥ 123.45"))
        assertNull(AlipayVisualPolicy.cropAmount("123.45 124.45"))
        assertNull(AlipayVisualPolicy.cropAmount("支付编号12345678"))
    }
}
