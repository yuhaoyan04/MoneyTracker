package com.mudasir.smartledger

import com.mudasir.smartledger.capture.VisualGuardTargets
import com.mudasir.smartledger.capture.VisualHandoffState
import org.junit.Assert.*
import org.junit.Test

class VisualGuardTargetsTest {
    @Test fun `sku experiment is limited to the observed taobao dialog`() {
        val cls = "com.taobao.android.sku.widget.SkuDialogFragment\$1"
        assertTrue(VisualGuardTargets.isTaobaoSkuDialog("com.taobao.taobao", cls))
        assertFalse(VisualGuardTargets.isTaobaoSkuDialog("com.other", cls))
        assertFalse(VisualGuardTargets.isTaobaoSkuDialog("com.taobao.taobao", "android.app.Dialog"))
    }
    @Test fun `only the same fresh taobao checkout event retains a candidate`() {
        val tb = "com.taobao.taobao"
        val checkout = "com.alibaba.android.ultron.UltronTradeHybridActivity"
        assertTrue(VisualGuardTargets.isFreshDuplicateCheckout(tb, checkout, tb, checkout, 1000, 1900))
        assertTrue(VisualGuardTargets.isFreshDuplicateCheckout(tb, "com.taobao.TBOrderListActivity", tb, "taobao_order_list_nodes", 1000, 1900))
        assertFalse(VisualGuardTargets.isFreshDuplicateCheckout(tb, checkout, tb, checkout, 1000, 2001))
        assertFalse(VisualGuardTargets.isFreshDuplicateCheckout(tb, checkout, tb, "another.Activity", 1000, 1900))
        assertFalse(VisualGuardTargets.isFreshDuplicateCheckout(tb, checkout, "com.other", checkout, 1000, 1900))
        assertFalse(VisualGuardTargets.isFreshDuplicateCheckout(tb, checkout, tb, checkout, 0, 500))
    }
    @Test fun `jd verification is distinct from an order page`() {
        val jd = "com.jingdong.app.mall"
        val challenge = "com.jingdong.app.mall.bundle.jdrhsdk.JDRiskHandleActivity"
        val order = "com.jd.lib.settlement.taro.activity.SettlementFillOrderTaroActivity"
        assertTrue(VisualGuardTargets.isJdVerification(jd, challenge))
        assertFalse(VisualGuardTargets.sourceActivity(jd, challenge))
        assertTrue(VisualGuardTargets.isJdOrderAfterVerification(jd, order))
        assertFalse(VisualGuardTargets.isJdOrderAfterVerification("com.other", order))
    }
    @Test fun `opt in gates each source and excludes unknown packages`() {
        assertTrue(VisualGuardTargets.sourceAllowed("com.taobao.taobao", true, false))
        assertFalse(VisualGuardTargets.sourceAllowed("com.jingdong.app.mall", true, false))
        assertTrue(VisualGuardTargets.sourceAllowed("com.jingdong.app.mall", false, true))
        assertTrue(VisualGuardTargets.sourceAllowed("com.xunmeng.pinduoduo", false, true))
        assertTrue(VisualGuardTargets.sourceAllowed("com.sankuai.meituan", false, true))
        assertFalse(VisualGuardTargets.sourceAllowed("com.unknown.shop", true, true))
    }
    @Test fun `wechat requires recent entry and consumes source candidate once`() {
        val state = VisualHandoffState()
        state.remember("com.jingdong.app.mall", 600.0, 1000)
        assertNull(state.observe("com.tencent.mm", "com.tencent.mm.plugin.lite.ui.WxaLiteAppTransparentLiteUI", 2000))
        assertNull(state.observe("com.tencent.mm", "com.tencent.mm.plugin.wallet_index.ui.OrderHandlerUI", 2500))
        assertEquals(600.0, state.observe("com.tencent.mm", "com.tencent.mm.plugin.lite.ui.WxaLiteAppTransparentLiteUI", 3000)!!, 0.001)
        assertNull(state.observe("com.tencent.mm", "com.tencent.mm.plugin.lite.ui.WxaLiteAppTransparentLiteUI", 4000))
    }
    @Test fun `other cashiers require allowlisted target and recent candidate`() {
        val state = VisualHandoffState()
        state.remember("com.xunmeng.pinduoduo", 600.0, 1000)
        assertNull(state.observe("com.fake.bank", "CashierActivity", 2000))
        assertNull(state.observe("com.eg.android.AlipayGphone", "MainActivity", 2000))
        assertNull(state.observe("com.eg.android.AlipayGphone", "CashierActivity", 3000))
        assertEquals(600.0, state.observe("com.icbc", "CashierActivity", 3000)!!, 0.001)
        state.remember("com.sankuai.meituan", 800.0, 1000)
        assertNull(state.observe("com.icbc", "PaymentActivity", 21_001))
    }
}
