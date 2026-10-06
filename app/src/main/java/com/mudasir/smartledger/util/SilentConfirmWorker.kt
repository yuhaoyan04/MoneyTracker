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

/**
 * 静默确认 Worker：每日检查 PENDING 记录，将超过 7 天未修正的记录自动确认。
 *
 * 自进化闭环：
 * - 用户确认 → learn()（显式反馈）
 * - 用户修正 → correct()（显式反馈）
 * - 用户无视 → 7天后静默确认 → learn()（隐式反馈：预测正确）
 *
 * 这确保了即使用户不主动确认每条记录，模型也能从正确预测中学习。
 */
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
            val cutoff = System.currentTimeMillis() - SILENT_CONFIRM_DAYS * 24 * 60 * 60 * 1000L
            val dao = AppDatabase.getDatabase(applicationContext).transactionDao()
            val oldPending = dao.getPendingOlderThan(cutoff)

            if (oldPending.isEmpty()) return Result.success()

            var learned = 0
            for (record in oldPending) {
                dao.updateStatus(record.id, TransactionRecord.STATUS_CONFIRMED)
                if (record.categoryName.isNotBlank()) {
                    PersonalTagger.learn(
                        applicationContext,
                        record.type,
                        record.timestamp,
                        record.amount,
                        record.channelName,
                        record.categoryName,
                        record.merchant
                    )
                    learned++
                }
            }

            Log.i(TAG, "Silent-confirmed ${oldPending.size} pending records (learned=$learned)")
            // 待确认数量变化 → 刷新常驻通知
            PendingNotifier.update(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Silent confirm failed", e)
            Result.retry()
        }
    }
}
