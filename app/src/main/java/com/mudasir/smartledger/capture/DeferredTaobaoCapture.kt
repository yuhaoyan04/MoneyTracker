package com.mudasir.smartledger.capture

/** Correlates one dialog screenshot with the immediately following WeChat handoff. */
class DeferredTaobaoCapture {
    private var ticket = 0L
    private var opened = 0L
    private var left = 0L
    private var entry = false
    private var cashier = false

    fun open(now: Long): Long {
        ticket++
        opened = now; left = 0; entry = false; cashier = false
        return ticket
    }

    fun invalidate() { ticket++; opened = 0; left = 0; entry = false; cashier = false }

    fun window(pkg: String, cls: String, at: Long, isForeground: Boolean) {
        if (opened == 0L || at < opened) return
        if (pkg == VisualGuardTargets.WECHAT && isForeground &&
            (cls.endsWith("UI") || cls.endsWith("Activity")) &&
            !VisualGuardTargets.isWechatEntry(pkg, cls) && !VisualGuardTargets.isWechatCashier(pkg, cls)) {
            invalidate()
            return
        }
        if (pkg == VisualGuardTargets.TAOBAO && VisualGuardTargets.isSourcePaymentTransition(cls) || pkg == VisualGuardTargets.WECHAT) {
            if (left == 0L) left = at
            if (VisualGuardTargets.isWechatEntry(pkg, cls)) entry = true
            if (entry && VisualGuardTargets.isWechatCashier(pkg, cls)) cashier = true
        } else if (isForeground && pkg != VisualGuardTargets.TAOBAO) invalidate()
    }

    data class Observed(val entry: Boolean, val cashier: Boolean)

    fun accept(token: Long, capture: Long, now: Long, currentPackage: String?, allowSource: Boolean = false): Observed? {
        if (token != ticket || opened == 0L || left == 0L ||
            (currentPackage != VisualGuardTargets.WECHAT && !(allowSource && currentPackage == VisualGuardTargets.TAOBAO)) ||
            capture !in opened..left || left - capture > 3_000L || now - capture !in 0..5_000L) return null
        return Observed(entry, cashier)
    }
}
