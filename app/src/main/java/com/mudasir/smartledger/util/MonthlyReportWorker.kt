package com.mudasir.smartledger.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mudasir.smartledger.R
import com.mudasir.smartledger.activity.StatsActivity
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 月报就绪提醒：每月 1-3 日检查一次，上月账单生成后通知用户查看/分享。
 */
class MonthlyReportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "monthly_report_daily"
        private const val CHANNEL_ID = "monthly_report"

        fun scheduleDaily(context: Context) {
            val req = PeriodicWorkRequestBuilder<MonthlyReportWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(8, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            val cal = Calendar.getInstance()
            // 只在每月前 3 天提醒（生成上月账单）
            if (cal.get(Calendar.DAY_OF_MONTH) > 3) return@withContext Result.success()

            val prefs = applicationContext.getSharedPreferences("report_prefs", Context.MODE_PRIVATE)
            val lastCal = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }
            val ymKey = "%04d-%02d".format(lastCal.get(Calendar.YEAR), lastCal.get(Calendar.MONTH) + 1)
            if (prefs.getString("last_notified", "") == ymKey) return@withContext Result.success()

            val db = AppDatabase.getDatabase(applicationContext)
            val start = Calendar.getInstance().apply { set(lastCal.get(Calendar.YEAR), lastCal.get(Calendar.MONTH), 1, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
            val end = start.clone() as Calendar
            end.add(Calendar.MONTH, 1)
            val recs = db.transactionDao().getInRange(start.timeInMillis, end.timeInMillis)
            if (recs.isEmpty()) return@withContext Result.success()
            val income = recs.filter { it.type == TransactionRecord.TYPE_INCOME }.sumOf { it.amount }
            val expense = recs.filter { it.type == TransactionRecord.TYPE_EXPENSE }.sumOf { it.amount }

            notify(ymKey, income, expense)
            prefs.edit().putString("last_notified", ymKey).apply()
        }
        Result.success()
    }

    private fun notify(ymKey: String, income: Double, expense: Double) {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "月度账单", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "每月初提醒查看上月账单"
                }
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return

        val monthNum = ymKey.substringAfter('-').removePrefix("0")
        val intent = android.content.Intent(ctx, StatsActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = android.app.PendingIntent.getActivity(
            ctx, 4001, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_inbox)
            .setContentTitle("📊 你的 ${monthNum} 月账单已生成")
            .setContentText("支出 ¥${"%,.0f".format(expense)} · 收入 ¥${"%,.0f".format(income)} · 点击查看月报卡片")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(4001, n)
    }
}
