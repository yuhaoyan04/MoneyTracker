package com.mudasir.smartledger.util

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class BackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            AutoBackupManager.backup(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "moneytracker_backup_periodic"

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(12, TimeUnit.HOURS)
                .setInitialDelay(5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun runNow(context: Context) {
            kotlinx.coroutines.runBlocking { AutoBackupManager.backup(context.applicationContext) }
        }
    }
}
