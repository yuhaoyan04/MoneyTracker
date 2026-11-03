package com.mudasir.smartledger.capture

data class ResumedPage(val pkg: String, val cls: String, val timestamp: Long) {
    val key: String get() = "$timestamp|$pkg|$cls"
}

/** Select a fresh payment hint only while its app is still the latest resumed app. */
object UsagePaymentEvents {
    fun candidate(events: List<ResumedPage>, now: Long, seen: Set<String>, includeShoppingCheckout: Boolean = false): ResumedPage? {
        val current = events.lastOrNull()?.pkg ?: return null
        return events.asReversed().firstOrNull {
            it.pkg == current && it.timestamp in (now - 5_000L)..now &&
                it.key !in seen && PaymentScreenClassifier.matches(it.pkg, it.cls, includeShoppingCheckout)
        }
    }
}
