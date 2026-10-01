package com.mudasir.smartledger.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通知监听服务：自动抓取微信 / 支付宝 / 淘宝 / 京东 / 银行 App 的支付通知。
 *
 * 抓取的交易以 status = PENDING 写入「待确认收件箱」，由用户核对后确认，
 * 既保证不遗漏，又允许用户修正分类/渠道/金额。
 *
 * 使用前需用户在系统「通知使用权」中授权本应用。
 */
class LedgerNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recentHashes = LinkedHashMap<Long, String>()

    // 商户关联缓冲区：记录最近解析的通知（含商户名），用于跨 App 商户补全
    private data class RecentCapture(
        val amount: Double,
        val merchant: String?,
        val pkg: String,
        val timestamp: Long
    )
    private val recentCaptures = mutableListOf<RecentCapture>()

    // 渠道名集合 —— 如果 merchant 字段只是渠道名（如"微信支付"），则视为缺失商户
    private val channelNames = setOf(
        "微信支付", "微信", "支付宝", "京东", "淘宝", "天猫", "银行卡", "银行",
        "信用卡", "花呗", "借呗", "云闪付", "数字人民币", "现金", "其他", "android"
    )

    // 监听这些包名的通知。null 表示来源未知，但仍尝试解析。
    private val watchedPackages = setOf(
        "com.tencent.mm",           // 微信
        "com.eg.android.AlipayGphone", // 支付宝
        "com.taobao.taobao",        // 淘宝
        "com.jingdong.app.mall",    // 京东
        "com.jd.jrapp",             // 京东金融
        "com.eg.android.AlipayGphone.rc"
    )

    // 促销 / 广告黑名单 —— 命中即丢弃，不进入收件箱
    private val promotionalBlacklist = listOf(
        "广告", "促销", "优惠", "立减", "满减", "折扣", "领取", "福利", "活动",
        "邀请", "推广", "积分", "签到", "优惠券", "红包雨", "抽奖", "推荐",
        "降价", "新品", "限时", "抢购", "薪资", "月薪", "好友", "拼团", "砍价",
        "免费", "赠送", "中奖", "补贴", "新人专享", "热卖", "爆款", "种草",
        "好友拼", "帮砍", "直播间", "开播", "预告", "更新", "评论", "点赞",
        "关注", "粉丝", "消息提醒", "验证码", "动态", "好友请求", "申请",
        "面试", "简历", "职位", "hr", "boss直聘", "拉勾", "猎聘", "前程无忧",
        "智联", "51job", "zhilian", "商品降价", "购物车", "收藏", "评价",
        "返现", "提现到账通知", "收益到账", "结算", "结算成功"
    )

    // 支付动作关键词 —— 双信号之一（必须同时有金额符号才认定为支付通知）
    private val paymentActionKeywords = listOf(
        "支付成功", "支付", "消费", "付款成功", "付款", "扣款", "自动扣款", "代扣",
        "已支付", "刷卡", "花费", "已扣", "收款", "到账", "入账", "退款",
        "退回", "转入", "已退款", "退款成功", "转账", "代付"
    )

    // 金额符号 —— 双信号之二
    private fun hasAmountSymbol(text: String): Boolean =
        text.contains("¥") || text.contains("￥") || text.contains("元")

    private fun isPromotional(text: String): Boolean {
        val lower = text.lowercase()
        return promotionalBlacklist.any { lower.contains(it.lowercase()) }
    }

    private fun hasPaymentAction(text: String): Boolean {
        val lower = text.lowercase()
        return paymentActionKeywords.any { lower.contains(it.lowercase()) }
    }

    /** 判断 merchant 字段是否为空或只是渠道名（需要跨 App 补全） */
    private fun needsMerchant(merchant: String?): Boolean {
        if (merchant.isNullOrBlank()) return true
        val lower = merchant.lowercase()
        return channelNames.any { lower.contains(it.lowercase()) }
    }

    /** 在 60s 窗口内查找金额匹配、来源不同的通知，补全商户名 */
    private fun correlateMerchant(amount: Double, pkg: String, timestamp: Long): String? {
        val now = System.currentTimeMillis()
        synchronized(recentCaptures) {
            recentCaptures.removeAll { it.timestamp < now - 60_000 }
            return recentCaptures.find {
                kotlin.math.abs(it.amount - amount) < 0.01 &&
                it.pkg != pkg &&
                !needsMerchant(it.merchant)
            }?.merchant
        }
    }

    /** 将本次抓取存入缓冲区，供后续通知关联 */
    private fun storeCapture(amount: Double, merchant: String?, pkg: String, timestamp: Long) {
        synchronized(recentCaptures) {
            recentCaptures.add(RecentCapture(amount, merchant, pkg, timestamp))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

        val combined = listOf(title, sub, text, big).filter { it.isNotBlank() }.joinToString(" | ")
        if (combined.isBlank()) return

        // === Gate 1: 促销/广告黑名单 —— 直接丢弃 ===
        if (isPromotional(combined)) return

        // === Gate 2: 双信号门 —— 支付动作 + 金额符号 必须同时满足 ===
        val watched = pkg in watchedPackages
        val hasAction = hasPaymentAction(combined)
        val hasAmount = hasAmountSymbol(combined)

        if (!hasAction || !hasAmount) {
            if (!watched) return
            if (!hasAction) return
        }

        val parsed = TransactionParser.parse(
            text = combined,
            source = TransactionRecord.SOURCE_CAPTURE_NOTIFICATION,
            packageName = pkg,
            timestamp = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        )

        scope.launch {
            val dao = AppDatabase.getDatabase(this@LedgerNotificationListener).transactionDao()
            if (parsed != null) {
                if (isDuplicate(parsed)) {
                    // 跨 App 去重：同一笔交易被多个 App 通知。
                    // 若本次通知带有商户名，尝试补全已入库记录的空商户。
                    if (!needsMerchant(parsed.merchant)) {
                        val cutoff = parsed.timestamp - 60_000
                        dao.updatePendingMerchant(parsed.merchant!!, parsed.amount, cutoff)
                    }
                    return@launch
                }
                val rec = parsed.toRecord()
                // 商户关联：若当前通知缺少商户，从 60s 窗口内其他 App 的通知补全
                val enrichedMerchant = if (needsMerchant(rec.merchant)) {
                    correlateMerchant(rec.amount, pkg, rec.timestamp) ?: rec.merchant
                } else {
                    rec.merchant
                }
                // 存入缓冲区供后续通知关联
                storeCapture(rec.amount, enrichedMerchant, pkg, rec.timestamp)
                // 个性化打标冷启动：抓取时即给一个分类建议，减少用户手动分类负担
                val suggested = com.mudasir.smartledger.ml.PersonalTagger.recommend(
                    applicationContext,
                    rec.type, rec.timestamp, rec.amount, rec.channelName, enrichedMerchant, rec.rawText
                )
                dao.insert(rec.copy(
                    categoryName = rec.categoryName.ifBlank { suggested },
                    merchant = enrichedMerchant
                ))
            } else if (watched && hasAction) {
                // 兜底：监听包内疑似支付但解析失败 —— 仍以原文入库，确保不遗漏
                val fallbackMerchant = title.takeIf { it.isNotBlank() && !needsMerchant(title) }
                    ?: correlateMerchant(0.0, pkg, sbn.postTime)
                dao.insert(
                    TransactionRecord(
                        type = TransactionRecord.TYPE_EXPENSE,
                        amount = 0.0,
                        channelName = TransactionParser.channelForPackage(pkg) ?: "其他",
                        merchant = fallbackMerchant,
                        rawText = combined,
                        source = TransactionRecord.SOURCE_CAPTURE_NOTIFICATION,
                        packageName = pkg,
                        status = TransactionRecord.STATUS_PENDING,
                        timestamp = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private fun isDuplicate(parsed: ParsedTransaction): Boolean {
        val now = System.currentTimeMillis()
        // 跨应用去重：同一笔交易可能同时被微信和银行App通知，
        // 按 type + amount + 分钟桶匹配，忽略渠道差异
        val hash = "${parsed.type}|${parsed.amount}|${parsed.timestamp / 60000}"
        // 清理 3 分钟以上的旧记录（扩大窗口以覆盖跨应用延迟）
        val cutoff = now - 180_000
        val it = recentHashes.entries.iterator()
        while (it.hasNext()) {
            if (it.next().key < cutoff) it.remove() else break
        }
        return if (recentHashes.values.contains(hash)) true
        else { recentHashes[now] = hash; false }
    }

    private fun ParsedTransaction.toRecord() = TransactionRecord(
        type = type,
        amount = amount,
        channelName = channelName,
        paymentMethod = paymentMethod,
        merchant = merchant,
        rawText = rawText,
        source = source,
        packageName = packageName,
        status = TransactionRecord.STATUS_PENDING,
        timestamp = timestamp
    )
}
