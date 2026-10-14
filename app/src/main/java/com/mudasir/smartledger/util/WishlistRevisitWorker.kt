package com.mudasir.smartledger.util

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mudasir.smartledger.R
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.WishlistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 愿望清单 24 小时冷静回访：
 * 记录「我想买」24 小时后，通知询问「还想买吗」——通知上可直接点
 * 「还想买 / 不要了」，不要了计入「已省下」金额。
 */
class WishlistRevisitWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        fun schedule(context: Context, itemId: Long) {
            val req = OneTimeWorkRequestBuilder<WishlistRevisitWorker>()
                .setInitialDelay(24, TimeUnit.HOURS)
                .setInputData(workDataOf("item_id" to itemId))
                .addTag("wishlist_revisit")
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("wishlist_revisit_$itemId", ExistingWorkPolicy.REPLACE, req)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getLong("item_id", -1L)
        if (id <= 0) return@withContext Result.success()

        val dao = AppDatabase.getDatabase(applicationContext).wishlistDao()
        val item = dao.getById(id) ?: return@withContext Result.success()
        // 用户已提前处理（购买/放弃）→ 不打扰
        if (item.status != WishlistItem.STATUS_WAITING) return@withContext Result.success()

        WishlistNotifier.notifyRevisit(applicationContext, item)
        Result.success()
    }
}
