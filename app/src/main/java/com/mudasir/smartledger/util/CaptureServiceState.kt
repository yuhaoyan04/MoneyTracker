package com.mudasir.smartledger.util

import android.content.Context

/**
 * 区分“系统设置中已授权”和“服务已实际绑定”。
 *
 * Android 不允许应用自行打开无障碍/通知使用权。这里仅记录本进程内系统回调，
 * 用于向用户准确显示绑定失败，而不是把“授权条目存在”误报成“正在运行”。
 */
object CaptureServiceState {
    @Volatile private var accessibilityConnected = false
    @Volatile private var notificationConnected = false

    fun setAccessibilityConnected(context: Context, connected: Boolean) {
        accessibilityConnected = connected
        if (connected) mark(context, "accessibility_last_connected")
    }

    fun setNotificationConnected(context: Context, connected: Boolean) {
        notificationConnected = connected
        if (connected) mark(context, "notification_last_connected")
    }

    fun isAccessibilityConnected(): Boolean = accessibilityConnected
    fun isNotificationConnected(): Boolean = notificationConnected

    fun accessibilityLastConnectedAt(context: Context): Long = last(context, "accessibility_last_connected")
    fun notificationLastConnectedAt(context: Context): Long = last(context, "notification_last_connected")

    private fun mark(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(key, System.currentTimeMillis()).apply()
    }

    private fun last(context: Context, key: String): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(key, 0L)

    private const val PREFS = "capture_service_state"
}
