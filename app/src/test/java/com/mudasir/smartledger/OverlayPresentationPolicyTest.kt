package com.mudasir.smartledger

import com.mudasir.smartledger.capture.OverlayPresentationPolicy
import com.mudasir.smartledger.capture.OverlayPresentationPolicy.Choice
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPresentationPolicyTest {
    @Test fun `structured and visual prompts use accessibility window when available`() {
        listOf("com.eg.android.AlipayGphone", "com.tencent.mm", "com.taobao.taobao").forEach {
            assertEquals(Choice.ACCESSIBILITY, OverlayPresentationPolicy.choose(it, true))
        }
    }
    @Test fun `alipay falls back to a notification if accessibility window is unavailable`() {
        assertEquals(Choice.NOTIFICATION, OverlayPresentationPolicy.choose("com.eg.android.AlipayGphone", false))
        assertEquals(Choice.APPLICATION, OverlayPresentationPolicy.choose("com.taobao.taobao", false))
    }
}
