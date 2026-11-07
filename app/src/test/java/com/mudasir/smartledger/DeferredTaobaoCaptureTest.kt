package com.mudasir.smartledger

import com.mudasir.smartledger.capture.DeferredTaobaoCapture
import com.mudasir.smartledger.capture.VisualGuardTargets
import com.mudasir.smartledger.capture.VisualHandoffState
import org.junit.Assert.*
import org.junit.Test

class DeferredTaobaoCaptureTest {
    private val taobao = VisualGuardTargets.TAOBAO
    private val wechat = VisualGuardTargets.WECHAT

    @Test fun lateResultAcceptsOnlyCapturedDialogAndReplaysObservedWindows() {
        val state = DeferredTaobaoCapture()
        val ticket = state.open(1000)
        state.window(taobao, "CashDeskActivity", 1900, true)
        state.window(wechat, "OrderHandlerUI", 2100, true)
        state.window(wechat, "WxaLiteAppTransparentLiteUI", 2300, true)
        val accepted = state.accept(ticket, 1200, 3500, wechat)!!
        assertTrue(accepted.entry); assertTrue(accepted.cashier)
        assertNull(state.accept(ticket, 1950, 3500, wechat))
        assertNull(state.accept(ticket, 1200, 7000, wechat))
        assertNull(state.accept(ticket, 1200, 3500, taobao))
    }

    @Test fun leavingTheFlowOrOpeningAnotherDialogInvalidatesTheOldCapture() {
        val state = DeferredTaobaoCapture()
        val ticket = state.open(1000)
        state.window(taobao, "CashDeskActivity", 1900, true)
        state.window("other.app", "MainActivity", 2000, true)
        assertNull(state.accept(ticket, 1200, 2200, wechat))
        val second = state.open(3000)
        state.open(3100)
        assertNull(state.accept(second, 3050, 3200, wechat))
    }

    @Test fun leavingCashierForWechatHomeInvalidatesPendingResult() {
        val state = DeferredTaobaoCapture()
        val ticket = state.open(1000)
        state.window(taobao, "CashDeskActivity", 1900, true)
        state.window(wechat, "OrderHandlerUI", 2100, true)
        state.window(wechat, "WxaLiteAppTransparentLiteUI", 2200, true)
        state.window(wechat, "LauncherUI", 2300, true)
        assertNull(state.accept(ticket, 1200, 2400, wechat))
    }

    @Test fun lateResultCanWaitForWechatButExpiresQuickly() {
        val capture = DeferredTaobaoCapture()
        val ticket = capture.open(1000)
        capture.window(taobao, "CashDeskActivity", 1900, true)
        assertNotNull(capture.accept(ticket, 1200, 2100, taobao, allowSource = true))
        val handoff = VisualHandoffState()
        handoff.remember(taobao, 100.0, 2100, 5000)
        handoff.observe(wechat, "OrderHandlerUI", 2500)
        assertEquals(100.0, handoff.observe(wechat, "WxaLiteAppTransparentLiteUI", 3000)!!, 0.0)
        handoff.remember(taobao, 100.0, 2100, 5000)
        handoff.observe(wechat, "OrderHandlerUI", 7101)
        assertNull(handoff.observe(wechat, "WxaLiteAppTransparentLiteUI", 7200))
    }
}
