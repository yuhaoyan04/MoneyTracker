package com.mudasir.smartledger

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.NoMatchingViewException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mudasir.smartledger.activity.AddEditTransactionActivity
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real picker against an isolated application and empty amount. */
@RunWith(AndroidJUnit4::class)
class CategorySelectionFlowTest {
    @Test fun categorySelectionAndTypeSwitchStayConsistent() {
        ActivityScenario.launch(AddEditTransactionActivity::class.java).use {
            onView(withId(R.id.tvCategoryValue)).check(matches(withText("未选择")))
            onView(withId(R.id.cardCategory)).perform(click())
            // The category list is loaded from Room asynchronously.
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000L
            while (true) {
                try { onView(withText("个人护理")).check(matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed())); break }
                catch (e: NoMatchingViewException) {
                    if (android.os.SystemClock.uptimeMillis() >= deadline) throw e
                    Thread.sleep(200)
                }
            }
            onView(withText("个人护理")).perform(scrollTo(), click())
            onView(withText("美妆护肤")).perform(click())
            onView(withId(R.id.tvCategoryValue)).check(matches(withText("美妆护肤")))
            onView(withId(R.id.btnExpense)).perform(click())
            onView(withId(R.id.tvCategoryValue)).check(matches(withText("美妆护肤")))
            onView(withId(R.id.cardChannel)).perform(click())
            onView(withText("支付宝")).perform(scrollTo(), click())
            onView(withId(R.id.tvChannelValue)).check(matches(withText("支付宝")))
            onView(withId(R.id.cardPayment)).perform(click())
            onView(withText("储蓄卡")).perform(scrollTo(), click())
            onView(withId(R.id.tvPaymentValue)).check(matches(withText("储蓄卡")))
            onView(withId(R.id.btnIncome)).perform(click())
            onView(withId(R.id.tvCategoryValue)).check(matches(withText("未选择")))
        }
    }
}
