package com.mudasir.smartledger

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.mudasir.smartledger.activity.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsLayoutTest {
    @Test fun sectionsExpandAndKeepTheirStateAfterRecreation() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val body = activity.findViewById<View>(R.id.bodyGuard)
                assertEquals(View.GONE, body.visibility)
                activity.findViewById<View>(R.id.headerGuard).performClick()
                assertEquals(View.VISIBLE, body.visibility)
                assertNotNull(activity.findViewById<View>(R.id.btnThreshold))
                assertNotNull(activity.findViewById<View>(R.id.btnGuardDebug))
                assertNotNull(activity.findViewById<View>(R.id.btnImportBill))
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.bodyGuard).visibility)
            }
        }
    }

    @Test fun diagnosticSnapshotKeepsCopyAndRefreshAccessible() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.headerGuard).performClick()
                activity.findViewById<View>(R.id.btnGuardDebug).performClick()
            }
            onView(withText("复制诊断")).check(matches(isDisplayed())).perform(click())
            onView(withText("刷新")).check(matches(isDisplayed())).perform(click())
            onView(withText("复制诊断")).check(matches(isDisplayed()))
            onView(withText("关闭")).perform(click())
        }
    }
}
