package com.mudasir.smartledger

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mudasir.smartledger.activity.CaptureInboxActivity
import com.mudasir.smartledger.activity.AddEditTransactionActivity
import com.mudasir.smartledger.activity.HomeActivity
import com.mudasir.smartledger.activity.SettingsActivity
import com.mudasir.smartledger.activity.StatsActivity
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.util.AppearancePreferences
import com.mudasir.smartledger.util.CooldownOverlayFactory
import com.mudasir.smartledger.util.FormatUtil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar

/** Repeatable review images from synthetic data in a separate package, never the user's ledger. */
@RunWith(AndroidJUnit4::class)
class DesignReviewTest {
    @Test fun balanceAnimationEndsWithExactMonetaryValue(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".isolated"))
        context.getSharedPreferences("onboarding_prefs", 0).edit().putBoolean("done", true).commit()
        val dao = AppDatabase.getDatabase(context).transactionDao()
        val id = dao.insert(TransactionRecord(
            type = TransactionRecord.TYPE_INCOME, amount = 1_234_567.89,
            categoryName = "工资", status = TransactionRecord.STATUS_CONFIRMED
        ))
        try {
            val now = Calendar.getInstance()
            val records = dao.getActiveRaw().filter {
                it.year == now.get(Calendar.YEAR) && it.month0 == now.get(Calendar.MONTH)
            }
            val expected = records.sumOf { if (it.type == TransactionRecord.TYPE_INCOME) it.amount else -it.amount }
            ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
                Thread.sleep(1800)
                scenario.onActivity {
                    assertEquals(FormatUtil.money(expected), it.findViewById<TextView>(R.id.tvBalance).text.toString())
                }
            }
        } finally { dao.deleteById(id) }
    }

    @Test fun seededLightAndDarkScreensAndScrollableReminder(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".isolated")) { "Use -PisolatedDeviceTest=true" }
        val output = File(context.getExternalFilesDir(null), "design-review").apply { mkdirs() }
        context.getSharedPreferences("onboarding_prefs", 0).edit().putBoolean("done", true).commit()
        val dao = AppDatabase.getDatabase(context).transactionDao()
        val ids = mutableListOf<Long>()
        val previous = AppearancePreferences.selected(context)
        try {
            val calendar = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 12)
            }
            for (day in 1..12) {
                calendar.set(Calendar.DAY_OF_MONTH, day)
                val lunch = day % 3 != 0
                ids += dao.insert(TransactionRecord(
                    amount = if (lunch) 22.0 + day else 4.0,
                    categoryName = if (lunch) "正餐" else "公共交通",
                    merchant = if (lunch) "午间食堂 · 示例" else "城市地铁 · 示例",
                    channelName = "微信支付", timestamp = calendar.timeInMillis,
                    status = TransactionRecord.STATUS_CONFIRMED
                ))
            }
            ids += dao.insert(TransactionRecord(
                type = TransactionRecord.TYPE_INCOME, amount = 5000.0,
                categoryName = "工资", merchant = "月度收入 · 示例",
                status = TransactionRecord.STATUS_CONFIRMED
            ))
            ids += dao.insert(TransactionRecord(
                amount = 3.0, categoryName = "公共交通", channelName = "微信支付",
                merchant = "乘车扣款 · 示例", source = TransactionRecord.SOURCE_CAPTURE_NOTIFICATION
            ))
            for ((mode, suffix) in listOf(
                AppCompatDelegate.MODE_NIGHT_NO to "light",
                AppCompatDelegate.MODE_NIGHT_YES to "dark"
            )) {
                instrumentation.runOnMainSync { AppearancePreferences.save(context, mode) }
                capture(HomeActivity::class.java, "home-$suffix", output)
                capture(StatsActivity::class.java, "stats-$suffix", output)
                capture(CaptureInboxActivity::class.java, "inbox-$suffix", output)
                capture(SettingsActivity::class.java, "settings-$suffix", output)
                capture(AddEditTransactionActivity::class.java, "form-$suffix", output)
            }
            instrumentation.runOnMainSync {
                val view = CooldownOverlayFactory.create(context.applicationContext)
                view.findViewById<TextView>(R.id.tvCooldownSpend).text = "订单页应付 ¥680.00\n超过提醒门槛 ¥500.00 · 示例"
                view.findViewById<TextView>(R.id.tvCountdown).apply { text = "就绪"; textSize = 24f }
                view.findViewById<Button>(R.id.btnContinue).apply {
                    isEnabled = true; text = "我已三思，继续操作"
                }
                // Match a compact phone viewport; scrolling must retain access to both actions.
                val density = context.resources.displayMetrics.density
                val width = (400 * density).toInt()
                val height = (800 * density).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, height)
                assertTrue(view.findViewById<Button>(R.id.btnContinue).measuredHeight >= (48 * density).toInt())
                saveView(view, File(output, "reminder.png"))
            }
        } finally {
            ids.forEach { dao.deleteById(it) }
            instrumentation.runOnMainSync { AppearancePreferences.save(context, previous) }
        }
    }

    private fun <T : Activity> capture(activity: Class<T>, name: String, output: File) {
        ActivityScenario.launch(activity).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            // Room aggregation and the finite chart entrance animation finish before capture.
            Thread.sleep(1500)
            scenario.onActivity { saveView(it.window.decorView, File(output, "$name.png")) }
        }
    }

    private fun saveView(view: View, destination: File) {
        assertTrue(view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
