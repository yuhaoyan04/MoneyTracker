package com.mudasir.smartledger

import com.mudasir.smartledger.capture.PaymentAppLaunchTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentAppLaunchTrackerTest {
    @Test fun `shopping to wechat triggers once and ignores late replay`() {
        val tracker = PaymentAppLaunchTracker("ledger")
        assertFalse(tracker.observe("com.taobao.taobao", 1000))
        assertTrue(tracker.observe("com.tencent.mm", 2000))
        assertFalse(tracker.observe("com.tencent.mm", 2100))
        assertFalse(tracker.observe("com.taobao.taobao", 1000))
    }

    @Test fun `shopping to alipay is detected but unrelated or stale launch is not`() {
        val tracker = PaymentAppLaunchTracker("ledger")
        assertFalse(tracker.observe("com.jingdong.app.mall", 1000))
        assertTrue(tracker.observe("com.eg.android.AlipayGphone", 3000))
        assertFalse(tracker.observe("com.example.home", 4000))
        assertFalse(tracker.observe("com.tencent.mm", 5000))
        assertFalse(tracker.observe("com.taobao.taobao", 6000))
        assertFalse(tracker.observe("com.tencent.mm", 30000))
    }
}
