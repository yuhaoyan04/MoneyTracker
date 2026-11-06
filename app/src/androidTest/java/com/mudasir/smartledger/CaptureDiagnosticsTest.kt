package com.mudasir.smartledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mudasir.smartledger.capture.CaptureDiagnostics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureDiagnosticsTest {
    @Test fun optInCountsCanBeStoppedAndReset() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        CaptureDiagnostics.start(ctx)
        CaptureDiagnostics.record(ctx, CaptureDiagnostics.Stage.WECHAT_RECEIVED)
        CaptureDiagnostics.record(ctx, CaptureDiagnostics.Stage.SENDER_REJECTED)
        var report = CaptureDiagnostics.report(ctx)
        assertTrue(report.contains("收到微信通知（含普通聊天）：1"))
        assertTrue(report.contains("发送者未通过：1"))
        CaptureDiagnostics.stop(ctx)
        CaptureDiagnostics.record(ctx, CaptureDiagnostics.Stage.WECHAT_RECEIVED)
        assertTrue(CaptureDiagnostics.report(ctx).contains("收到微信通知（含普通聊天）：1"))
        CaptureDiagnostics.start(ctx)
        report = CaptureDiagnostics.report(ctx)
        assertTrue(report.contains("收到微信通知（含普通聊天）：0"))
        assertFalse(report.contains("发送者未通过：1"))
        CaptureDiagnostics.stop(ctx)
    }
}
