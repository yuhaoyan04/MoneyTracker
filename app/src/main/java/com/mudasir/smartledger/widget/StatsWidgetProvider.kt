package com.mudasir.smartledger.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.mudasir.smartledger.R
import com.mudasir.smartledger.activity.AddEditTransactionActivity
import com.mudasir.smartledger.activity.HomeActivity
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.util.FormatUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Calendar

/** 桌面小组件：今日支出 + 本月支出 + 快速记一笔。 */
class StatsWidgetProvider : AppWidgetProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(context: Context, mgr: AppWidgetManager, appWidgetIds: IntArray) {
        val result = goAsync()
        scope.launch {
            try {
                refresh(context, mgr, appWidgetIds)
            } finally {
                result.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // 清理该 widget 的重访任务占位（无持久状态，无需处理）
        super.onDeleted(context, appWidgetIds)
    }

    override fun onDisabled(context: Context) {
        scope.cancel()
        super.onDisabled(context)
    }

    companion object {
        /** 任意位置调用：刷新所有桌面小组件（抓取/记账/确认后自动同步）。 */
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, StatsWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                runCatching { refresh(context, mgr, ids) }
            }
        }

        private suspend fun refresh(context: Context, mgr: AppWidgetManager, ids: IntArray) {
            val dao = AppDatabase.getDatabase(context).transactionDao()
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val todayStart = cal.timeInMillis
            cal.set(Calendar.DAY_OF_MONTH, 1)
            val monthStart = cal.timeInMillis
            val now = System.currentTimeMillis() + 1

            val today = dao.getInRange(todayStart, now)
                .filter { it.type == TransactionRecord.TYPE_EXPENSE }
                .sumOf { it.amount }
            val month = dao.getInRange(monthStart, now)
                .filter { it.type == TransactionRecord.TYPE_EXPENSE }
                .sumOf { it.amount }

            val openHome = PendingIntent.getActivity(
                context, 3001,
                Intent(context, HomeActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val openAdd = PendingIntent.getActivity(
                context, 3002,
                Intent(context, AddEditTransactionActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val views = RemoteViews(context.packageName, R.layout.stats_widget).apply {
                setTextViewText(R.id.wToday, FormatUtil.money(today))
                setTextViewText(R.id.wMonth, "本月 ${FormatUtil.money(month)}")
                setOnClickPendingIntent(R.id.wToday, openHome)
                setOnClickPendingIntent(R.id.wMonth, openHome)
                setOnClickPendingIntent(R.id.wAdd, openAdd)
            }
            ids.forEach { id -> mgr.updateAppWidget(id, views) }
        }
    }
}
