package com.mudasir.smartledger.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 银行短信接收器：抓取银行下发的「消费/入账」短信，解析后写入待确认收件箱。
 *
 * 仅处理疑似银行交易短信（含金额 + 「尾号/银行/消费/到账」等关键词），
 * 避免把普通短信当作交易。
 *
 * 需 RECV_SMS 权限，并设为默认短信应用才能在部分机型拦截；此处仅被动接收。
 */
class BankSmsReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bankSignalKeywords = listOf("尾号", "银行", "信用社", "消费", "支出", "入账", "到账", "代发", "扣款", "退款")

    // 新闻/内容/通知短信黑名单 —— 命中即丢弃，避免新闻被误判为交易
    private val newsBlacklist = listOf(
        "男子", "女子", "网友", "爆料", "报道", "新闻", "事件", "吃出", "发现",
        "曝光", "维权", "投诉", "热搜", "刷屏", "意外", "震惊", "提醒大家",
        "注意了", "警惕", "骗局", "诈骗", "中奖通知", "验证码", "验证", "登录",
        "注册", "动态", "好友", "群消息", "公众号", "订阅", "取件", "快递柜",
        "取件码", "好评返现", "邀请你", "帮你砍", "拼团成功", "助力",
        "直播", "开播", "预告", "更新", "评论", "点赞", "关注", "粉丝",
        "面试", "简历", "职位", "hr", "boss直聘", "拉勾", "智联",
        "短信测试", "测试短信"
    )

    // 银行短信格式特征 —— 真正的银行交易短信通常包含这些结构化表述
    private val bankFormatPatterns = listOf(
        Regex("尾号\\d{4}"),
        Regex("账户尾号\\d{4}"),
        Regex("卡.*尾号\\d{4}"),
        Regex("\\d{4}的卡"),
        Regex("活期|定期|储蓄|信用|借记")
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val body = messages.joinToString("") { it.displayMessageBody ?: "" }
        if (body.isBlank()) return

        // 快速过滤非交易短信
        val hasMoney = body.contains("¥") || body.contains("￥") || body.contains("元")
        val hasBankSignal = bankSignalKeywords.any { body.contains(it) }
        if (!hasMoney || !hasBankSignal) return

        // 新闻/内容短信过滤 —— 命中新闻关键词直接丢弃
        val lowerBody = body.lowercase()
        if (newsBlacklist.any { lowerBody.contains(it.lowercase()) }) return

        // 银行短信格式验证 —— 至少匹配一个银行格式特征
        if (bankFormatPatterns.none { it.containsMatchIn(body) }) return

        val sender = messages.firstOrNull()?.displayOriginatingAddress
        val timestamp = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

        val parsed = TransactionParser.parse(
            text = body,
            source = TransactionRecord.SOURCE_CAPTURE_SMS,
            channelHint = "银行卡",
            timestamp = timestamp
        ) ?: return

        scope.launch {
            AppDatabase.getDatabase(context.applicationContext).transactionDao().insert(
                TransactionRecord(
                    type = parsed.type,
                    amount = parsed.amount,
                    channelName = parsed.channelName,
                    paymentMethod = parsed.paymentMethod,
                    merchant = parsed.merchant,
                    note = sender?.let { "来自 $it" },
                    rawText = body,
                    source = TransactionRecord.SOURCE_CAPTURE_SMS,
                    status = TransactionRecord.STATUS_PENDING,
                    timestamp = timestamp
                )
            )
        }
    }
}
