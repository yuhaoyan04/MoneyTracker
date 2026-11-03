package com.mudasir.smartledger

import com.mudasir.smartledger.capture.PaymentScreenClassifier
import com.mudasir.smartledger.capture.ResumedPage
import com.mudasir.smartledger.capture.UsagePaymentEvents
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentScreenClassifierTest {
    @Test fun `known payment activity hints are package specific`() {
        assertTrue(PaymentScreenClassifier.matches("com.tencent.mm", "com.tencent.mm.plugin.wallet.pay.ui.WalletPayUI"))
        assertTrue(PaymentScreenClassifier.matches("com.eg.android.AlipayGphone", "com.alipay.mobile.cashier.CashierActivity"))
        assertFalse(PaymentScreenClassifier.matches("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"))
        assertFalse(PaymentScreenClassifier.matches("com.eg.android.AlipayGphone", "com.alipay.mobile.main.HomeActivity"))
        assertFalse(PaymentScreenClassifier.matches("com.example.fake", "com.tencent.mm.plugin.wallet.pay.ui.WalletPayUI"))
        assertTrue(PaymentScreenClassifier.matches("com.tencent.mm", "com.tencent.mm.plugin.lite.ui.OrderHandlerUI"))
        assertFalse(PaymentScreenClassifier.matches("com.tencent.mm", "com.tencent.mm.plugin.lite.ui.WxaLiteAppTransparentLiteUI"))
        assertFalse(PaymentScreenClassifier.matches("com.taobao.taobao", "com.taobao.trade.CashDeskActivity"))
        assertTrue(PaymentScreenClassifier.matches("com.taobao.taobao", "com.taobao.trade.CashDeskActivity", true))
        assertTrue(PaymentScreenClassifier.matches("com.taobao.taobao", "com.taobao.trade.UltronTradeHybridActivity", true))
        assertFalse(PaymentScreenClassifier.matches("com.taobao.taobao", "com.taobao.trade.TBOrderListActivity"))
    }

    @Test fun `short lived observed entry is retained when followed by the wechat container`() {
        val entry = ResumedPage("com.tencent.mm", "com.tencent.mm.plugin.lite.ui.OrderHandlerUI", 1000)
        val container = ResumedPage("com.tencent.mm", "WxaLiteAppTransparentLiteUI", 1400)
        assertTrue(UsagePaymentEvents.candidate(listOf(entry, container), 1600, emptySet()) == entry)
        assertTrue(UsagePaymentEvents.candidate(listOf(entry, container), 1600, setOf(entry.key)) == null)
        assertTrue(UsagePaymentEvents.candidate(listOf(entry, container), 7000, emptySet()) == null)
        assertTrue(UsagePaymentEvents.candidate(listOf(entry, container, ResumedPage("com.taobao.taobao", "Home", 1500)), 1600, emptySet()) == null)
    }
}
