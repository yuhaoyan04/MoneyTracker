package com.mudasir.smartledger.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mudasir.smartledger.R
import com.mudasir.smartledger.activity.WishlistActivity
import com.mudasir.smartledger.data.WishlistItem

/** 愿望清单通知：24 小时回访「还想买吗」。 */
object WishlistNotifier {

    const val CHANNEL_ID = "wishlist_revisit"
    const val NOTIFICATION_ID = 3001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "愿望清单回访", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "24 小时冷静期结束，询问你是否仍然想买"
            }
        )
    }

    private fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun notifyRevisit(context: Context, item: WishlistItem) {
        ensureChannel(context)
        if (!hasPermission(context)) return

        val contentIntent = PendingIntent.getActivity(
            context, item.id.toInt(),
            Intent(context, WishlistActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val boughtPi = PendingIntent.getBroadcast(
            context, (item.id * 2).toInt(),
            WishlistActionReceiver.actionIntent(context, item.id, WishlistItem.STATUS_BOUGHT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dropPi = PendingIntent.getBroadcast(
            context, (item.id * 2 + 1).toInt(),
            WishlistActionReceiver.actionIntent(context, item.id, WishlistItem.STATUS_DROPPED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_inbox)
            .setContentTitle("🧘 24 小时到了 · 「${item.name}」还想买吗？")
            .setContentText("¥${"%.2f".format(item.amount)} · 冷静下来再做决定也不迟")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("¥${"%.2f".format(item.amount)}\n24 小时前你把它放进了愿望清单。冷静下来后，它还是必需的吗？"))
            .setContentIntent(contentIntent)
            .addAction(0, "还想买", boughtPi)
            .addAction(0, "不要了（省下 ¥${"%.0f".format(item.amount)}）", dropPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, n)
    }
}
