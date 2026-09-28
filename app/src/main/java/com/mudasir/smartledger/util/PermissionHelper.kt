package com.mudasir.smartledger.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils

object PermissionHelper {

    /** 通知使用权是否已授予本应用的 NotificationListenerService。 */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val target = ComponentName(context, "com.mudasir.smartledger.capture.LedgerNotificationListener").flattenToString()
        return flat.split(":").any { it == target }
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
}
