package com.mudasir.smartledger.capture

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Opt-in counts only; never stores notification or SMS content, amounts, or sender identities. */
object CaptureDiagnostics {
    enum class Stage(val label: String) {
        WECHAT_RECEIVED("收到微信通知（含普通聊天）"), OTHER_RECEIVED("收到其他受支持应用通知"),
        SENDER_REJECTED("发送者未通过"), CONTENT_REJECTED("内容规则未通过"),
        PARSED("通知金额解析成功"), FALLBACK("通知金额不明确，待补充"),
        SAVED("通知已保存到收件箱"), DUPLICATE("通知已存在"), FAILED("通知处理失败"),
        SMS_RECEIVED("收到短信广播"), SMS_REJECTED("短信未通过银行交易规则"),
        SMS_PARSED("银行短信解析成功"), SMS_UNPARSED("银行短信金额未解析"),
        SMS_SAVED("银行短信已保存"), SMS_FAILED("银行短信处理失败")
    }
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("capture_diagnostics", Context.MODE_PRIVATE)
    @Synchronized fun record(context: Context, stage: Stage) {
        val p = prefs(context)
        if (!p.getBoolean("enabled", false)) return
        p.edit().putLong(stage.name, p.getLong(stage.name, 0L) + 1)
            .putLong("last_time", System.currentTimeMillis()).apply()
    }
    @Synchronized fun start(context: Context) { prefs(context).edit().clear().putBoolean("enabled", true).apply() }
    @Synchronized fun stop(context: Context) { prefs(context).edit().putBoolean("enabled", false).apply() }
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    @Synchronized fun report(context: Context): String {
        val p = prefs(context)
        val time = p.getLong("last_time", 0L)
        val updated = if (time == 0L) "尚无记录" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
        return buildString {
            append("收件箱采集诊断：${if (p.getBoolean("enabled", false)) "记录中" else "已停止"}\n最近事件：$updated\n")
            Stage.entries.forEach { append("\n${it.label}：${p.getLong(it.name, 0L)}") }
            append("\n\n仅含计数和最后事件时间，不含通知、短信正文或金额。收到通知不等于识别为交易；普通微信聊天也会计入收到和发送者未通过。系统未投递的通知不会计入。")
        }
    }
}
