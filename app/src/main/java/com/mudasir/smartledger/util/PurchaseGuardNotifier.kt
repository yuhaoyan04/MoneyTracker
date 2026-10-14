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
import com.mudasir.smartledger.activity.CaptureInboxActivity
import com.mudasir.smartledger.activity.StatsActivity

/** 「想好再买」通知中枢：守护常驻 / 支付应用进入提醒 / 大额消费冷静提醒。 */
object PurchaseGuardNotifier {

    const val CHANNEL_ACTIVE = "guard_active"
    const val CHANNEL_THINK = "think_before_buy"
    const val CHANNEL_BIG_SPEND = "big_spend"

    private const val ID_ACTIVE = 2001
    private const val ID_THINK = 2002
    private const val ID_BIG_SPEND = 2003

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ACTIVE, "想好再买 · 守护", NotificationManager.IMPORTANCE_LOW).apply {
                description = "守护服务运行中的常驻标识"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_THINK, "想好再买 · 支付提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "进入支付应用时的冷静提醒"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BIG_SPEND, "大额消费提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "超过门槛的消费被抓取时立即提醒"
            }
        )
    }

    private fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 守护服务的常驻通知（低优先级，不打扰）。 */
    fun buildActive(context: Context): android.app.Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_ACTIVE)
            .setSmallIcon(R.drawable.ic_inbox)
            .setContentTitle("想好再买 · 守护中")
            .setContentText("进入支付应用时会提醒你三思")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** 进入支付应用 → 冷静提醒（本月已支出语境）。 */
    fun notifyThink(context: Context, monthExpense: Double, budgetPct: Int?) {
        ensureChannels(context)
        if (!hasPermission(context)) return
        val intent = Intent(context, StatsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, ID_THINK, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val budgetLine = budgetPct?.let { " · 预算已用 $it%" } ?: ""
        val n = NotificationCompat.Builder(context, CHANNEL_THINK)
            .setSmallIcon(R.drawable.ic_inbox)
            .setContentTitle("🧘 想好再买")
            .setContentText("本月已支出 ¥${"%.0f".format(monthExpense)}$budgetLine · 大额下单前让它先躺 24 小时")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("本月已支出 ¥${"%.0f".format(monthExpense)}$budgetLine\n大额下单前，让它先在购物车里躺 24 小时。"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ID_THINK, n)
    }

    fun cancelThink(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ID_THINK)
    }

    /** 大额消费抓取 → 立即冷静提醒。 */
    fun notifyBigSpend(context: Context, amount: Double, merchant: String) {
        ensureChannels(context)
        if (!hasPermission(context)) return
        val intent = Intent(context, CaptureInboxActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, ID_BIG_SPEND, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_BIG_SPEND)
            .setSmallIcon(R.drawable.ic_inbox)
            .setContentTitle("💰 大额消费 · 想好再买")
            .setContentText("¥${"%.2f".format(amount)} · $merchant — 已进收件箱，冷静一下再确认")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("¥${"%.2f".format(amount)} · $merchant\n已进入收件箱。冲动是魔鬼，冷静 10 分钟再确认也不迟。"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ID_BIG_SPEND, n)
    }
}
