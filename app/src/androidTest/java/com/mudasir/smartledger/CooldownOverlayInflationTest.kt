package com.mudasir.smartledger

import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mudasir.smartledger.util.CooldownOverlayFactory
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CooldownOverlayInflationTest {
    @Test fun inflateWithApplicationContext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = CooldownOverlayFactory.create(context)
            assertNotNull(view.findViewById<Button>(R.id.btnContinue))
            assertNotNull(view.findViewById<Button>(R.id.btnWishlist))
            assertNotNull(view.findViewById<TextView>(R.id.tvCountdown))
        }
    }
}
