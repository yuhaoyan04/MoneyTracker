package com.mudasir.smartledger

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mudasir.smartledger.activity.CaptureInboxActivity
import com.mudasir.smartledger.activity.HomeActivity
import com.mudasir.smartledger.activity.StatsActivity
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against the isolated test application and does not use the user's ledger. */
@RunWith(AndroidJUnit4::class)
class CoreScreensSmokeTest {
    @Test fun homeOpens() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("onboarding_prefs", 0).edit().putBoolean("done", true).commit()
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { assertNotNull(it.findViewById<View>(R.id.bottomNav)) }
        }
    }
    @Test fun inboxOpens() {
        ActivityScenario.launch(CaptureInboxActivity::class.java).use { scenario ->
            scenario.onActivity { assertNotNull(it.findViewById<View>(R.id.rvPending)) }
        }
    }
    @Test fun statisticsChartViewsOpen() {
        ActivityScenario.launch(StatsActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertNotNull(it.findViewById<View>(R.id.pieChart))
                assertNotNull(it.findViewById<View>(R.id.lineChartTrend))
                assertNotNull(it.findViewById<View>(R.id.barChartHourly))
            }
        }
    }
}
