package com.mudasir.smartledger.util

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.ml.PersonalTagger
import java.util.concurrent.TimeUnit

/** Optional automatic confirmation; this is not explicit training feedback. */
class SilentConfirmWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SilentConfirmWorker"
        private const val WORK_NAME = "smartledger_silent_confirm"
        private const val SILENT_CONFIRM_DAYS = 7L

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SilentConfirmWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            if (!applicationContext.getSharedPreferences("capture_review_prefs", Context.MODE_PRIVATE)
                    .getBoolean("auto_confirm_enabled", false)) return Result.success()
            val cutoff = System.currentTimeMillis() - SILENT_CONFIRM_DAYS * 24 * 60 * 60 * 1000L
            val dao = AppDatabase.getDatabase(applicationContext).transactionDao()
            val oldPending = dao.getPendingOlderThan(cutoff)

            if (oldPending.isEmpty()) return Result.success()

            var learned = 0
            var held = 0
            for (record in oldPending) {
                // 置信度门槛：低置信度预测不参与静默学习（自进化的数据质量防线）
                if (!CaptureReviewPolicy.canAutoConfirm(record.amount, record.categoryName, record.aiConfidence)) { held++; continue }
                if (dao.confirmPending(record.id) > 0) {
                    learned++
                }
            }

            Log.i(TAG, "Silent-confirmed ${learned} pending (held=$held ineligible)")
            // 待确认数量变化 → 刷新常驻通知
            PendingNotifier.update(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Silent confirm failed", e)
            Result.retry()
        }
    }
}
