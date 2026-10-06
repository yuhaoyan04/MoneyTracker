package com.mudasir.smartledger.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mudasir.smartledger.R
import com.mudasir.smartledger.activity.CaptureInboxActivity
import com.mudasir.smartledger.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 待确认常驻通知：
 * 只要收件箱还有 PENDING 交易，通知栏就保留一条常驻提醒；
 * 全部确认/忽略后自动消失。
 */
object PendingNotifier {

    private const val CHANNEL_ID = "pending_capture"
    private const val NOTIFICATION_ID = 1001

    /** 挂起调用：查询当前待确认数量并刷新/清除常驻通知。所有增删 PENDING 的地方都应调用。 */
    suspend fun update(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val dao = AppDatabase.getDatabase(context.applicationContext).transactionDao()
            val count = dao.countPending()
            if (count <= 0) {
                cancel(context)
            } else {
                val latest = dao.latestPending()
                notify(context, count, latest?.let { r ->
                    val title = r.merchant?.takeIf { it.isNotBlank() } ?: r.categoryName.ifBlank { "新交易" }
                    "${if (r.type == com.mudasir.smartledger.data.TransactionRecord.TYPE_INCOME) "+" else "-"}${r.amount} $title"
                })
            }
        }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "待确认交易提醒",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "有自动抓取的交易等待确认时，在通知栏常驻提醒"
                setShowBadge(true)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notify(context: Context, count: Int, latestLine: String?) {
        if (!hasPermission(context)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)

        val intent = Intent(context, CaptureInboxActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (latestLine != null) "最新：$latestLine" else "点击进入收件箱核对"
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_inbox)
            .setContentTitle("还有 $count 笔交易待确认")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)              // 常驻：不可滑动删除
            .setSilent(true)               // 静默刷新，不打扰
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    private fun cancel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }
}
