package com.mudasir.smartledger

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.PerformException
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mudasir.smartledger.activity.CaptureInboxActivity
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** A synthetic pending transaction is created only in the isolated test app. */
@RunWith(AndroidJUnit4::class)
class InboxConfirmFlowTest {
    @Test fun confirmMovesPendingToConfirmed() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("ledger_backup", Context.MODE_PRIVATE).edit()
            .putLong("last_public_snapshot", System.currentTimeMillis()).commit()
        val dao = AppDatabase.getDatabase(context).transactionDao()
        val id = dao.insert(TransactionRecord(amount = 12.34, categoryName = "正餐",
            merchant = "隔离测试商户", source = TransactionRecord.SOURCE_MANUAL,
            status = TransactionRecord.STATUS_PENDING))
        try {
            ActivityScenario.launch(CaptureInboxActivity::class.java).use {
                val deadline = android.os.SystemClock.uptimeMillis() + 10_000L
                while (true) {
                    try { onView(withId(R.id.btnConfirm)).perform(scrollTo(), click()); break }
                    catch (e: RuntimeException) {
                        if ((e !is NoMatchingViewException && e !is PerformException) || android.os.SystemClock.uptimeMillis() >= deadline) throw e
                        Thread.sleep(200)
                    }
                }
                withTimeout(10_000L) {
                    while (dao.getById(id)?.status != TransactionRecord.STATUS_CONFIRMED) delay(100)
                }
                assertEquals(TransactionRecord.STATUS_CONFIRMED, dao.getById(id)?.status)
                assertEquals(0, dao.confirmPending(id))
            }
        } finally { dao.deleteById(id) }
    }
}
