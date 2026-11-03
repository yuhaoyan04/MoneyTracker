package com.mudasir.smartledger

import com.mudasir.smartledger.capture.GuardPromptPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardPromptPolicyTest {
    @Test fun `prompt closes after changing apps but not for its own window`() {
        assertFalse(GuardPromptPolicy.shouldCloseOnForeground("com.taobao.taobao", "com.taobao.taobao", "ledger"))
        assertFalse(GuardPromptPolicy.shouldCloseOnForeground("com.taobao.taobao", "ledger", "ledger"))
        assertFalse(GuardPromptPolicy.shouldCloseOnForeground("com.taobao.taobao", "com.android.systemui", "ledger"))
        assertTrue(GuardPromptPolicy.shouldCloseOnForeground("com.taobao.taobao", "com.tencent.mm", "ledger"))
        assertTrue(GuardPromptPolicy.shouldCloseOnForeground("com.tencent.mm", "com.taobao.taobao", "ledger"))
    }

    @Test fun `only a recent pending handoff applies to wechat`() {
        assertTrue(GuardPromptPolicy.canUseTaobaoHandoff("com.tencent.mm", true, 1000, 2000, 120000))
        assertFalse(GuardPromptPolicy.canUseTaobaoHandoff("com.tencent.mm", false, 1000, 2000, 120000))
        assertFalse(GuardPromptPolicy.canUseTaobaoHandoff("com.tencent.mm", true, 1000, 122000, 120000))
        assertFalse(GuardPromptPolicy.canUseTaobaoHandoff("com.eg.android.AlipayGphone", true, 1000, 2000, 120000))
    }
}
