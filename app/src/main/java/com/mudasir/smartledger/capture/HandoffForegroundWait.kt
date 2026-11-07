package com.mudasir.smartledger.capture

/** Wait briefly for accessibility's active root to catch up with an observed cashier window. */
object HandoffForegroundWait {
    enum class Decision { DELIVER, WAIT, ABANDON }
    fun decide(target: String, activeRoot: String?, elapsedMs: Long, enabled: Boolean): Decision = when {
        !enabled || elapsedMs !in 0..1500L -> Decision.ABANDON
        target == activeRoot -> Decision.DELIVER
        else -> Decision.WAIT
    }
}
