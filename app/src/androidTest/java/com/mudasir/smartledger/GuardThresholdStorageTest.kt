package com.mudasir.smartledger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mudasir.smartledger.capture.GuardThreshold
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuardThresholdStorageTest {
    @Test fun preservesExactDecimalAndReadsLegacyPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("guard_threshold_test", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().putFloat("big_spend_threshold", 500f).commit()
            assertEquals(0, GuardThreshold.read(prefs).compareTo(BigDecimal("500")))
            prefs.edit().putString("big_spend_threshold_decimal", "1000000.01").commit()
            assertEquals(0, GuardThreshold.read(prefs).compareTo(BigDecimal("1000000.01")))
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
