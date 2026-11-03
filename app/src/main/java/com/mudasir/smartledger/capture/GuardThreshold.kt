package com.mudasir.smartledger.capture

import android.content.SharedPreferences
import java.math.BigDecimal

/** Exact decimal threshold, with compatibility for previously saved float values. */
object GuardThreshold {
    fun read(prefs: SharedPreferences): BigDecimal {
        val saved = runCatching { prefs.getString("big_spend_threshold_decimal", null) }.getOrNull()
        val legacy = runCatching { prefs.getFloat("big_spend_threshold", 500f).toString() }.getOrDefault("500")
        return runCatching { BigDecimal(saved ?: legacy) }.getOrDefault(BigDecimal("500"))
            .takeIf { it >= BigDecimal.ZERO && it <= BigDecimal("10000000") } ?: BigDecimal("500")
    }
}
