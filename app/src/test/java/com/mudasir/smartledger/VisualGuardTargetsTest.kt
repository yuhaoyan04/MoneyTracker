package com.mudasir.smartledger

import com.mudasir.smartledger.capture.VisualGuardTargets
import com.mudasir.smartledger.capture.VisualHandoffState
import org.junit.Assert.*
import org.junit.Test

class VisualGuardTargetsTest {
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
        assertEquals(600.0, state.observe("com.eg.android.AlipayGphone", "CashierActivity", 3000)!!, 0.001)
        state.remember("com.sankuai.meituan", 800.0, 1000)
        assertNull(state.observe("com.icbc", "PaymentActivity", 21_001))
    }
}
