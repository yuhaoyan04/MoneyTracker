package com.mudasir.smartledger.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService

object PermissionHelper {

    /** 通知使用权是否已授予本应用的 NotificationListenerService。 */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        val component = ComponentName(context, com.mudasir.smartledger.capture.LedgerNotificationListener::class.java)
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    /** 授权存在但系统未绑定时主动请求重连（Android 7+）。 */
    fun requestNotificationListenerRebind(context: Context) {
        if (!isNotificationListenerEnabled(context)) return
        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(context, com.mudasir.smartledger.capture.LedgerNotificationListener::class.java)
            )
        }
    }

    fun openNotificationListenerSettings(context: Context) {
        runCatching {
            context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun hasSmsPermission(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** 付款码抓取（无障碍服务）是否已启用。 */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val target = ComponentName(context, com.mudasir.smartledger.capture.PaymentAccessibilityService::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == target }
    }

    fun openAccessibilitySettings(context: Context) {
        runCatching {
            // Android 公共 API 只保证无障碍服务列表入口；各厂商详情页深链均为私有实现。
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
