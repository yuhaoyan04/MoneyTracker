package com.mudasir.smartledger.capture

/**
 * 通知通道采集门（纯逻辑，可单元测试）。
 *
 * v2.36.0 重构：广告金额误判收支的根治。
 *
 * 旧逻辑漏洞：`只有缺少完成词时才应用营销黑名单` —— 而营销文案只要碰巧
 * 含「交易成功/已存入」等词就能绕过全部过滤，广告金额入库。
 *
 * 新逻辑（宁缺勿滥）：
 *   Gate A  必须有支付动作词（支付/消费/付款/到账…）
 *   Gate B  必须有金额符号（¥/￥/元）
 *   Gate C  必须有「完成表述」强词（支付成功/已扣款/已到账…）—— 广告几乎
 *           从不携带这些结果性文案；漏采可手动补记，污染需逐条清理
 *   Gate D  完成词命中后再过营销黑名单兜底（拦截「支付成功再领红包」式标题党）
 */
object NotificationGate {

    /** WeChat also carries arbitrary chat messages, so require the payment service as sender. */
    fun isTrustedSender(pkg: String, title: String): Boolean =
        pkg != "com.tencent.mm" || title.trim() in setOf("微信支付", "微信收款助手", "微信支付凭证")

    /** 完成表述词（强）：真实支付/收款/退款结果文案 */
    val completionWords = listOf(
        // 支出结果
        "支付成功", "付款成功", "已支付", "已付款", "支付完成", "付款完成",
        "已扣款", "扣款成功", "扣款完成", "自动扣款成功", "刷卡成功",
        "交易完成", "交易成功", "已消费",
        "微信支付凭证", "付款凭证", "支付凭证",
        // 收入结果
        "收款成功", "收款到账", "已收款", "已收到转账", "转账成功", "已到账", "到账成功", "已存入", "已转入",
        // 退款结果
        "退款成功", "已退款", "退款完成", "退款已"
    )

    /** 营销黑名单：完成词命中后的兜底（标题党营销） */
    val promotionalBlacklist = listOf(
        "广告", "促销", "立减", "满减", "折扣", "领取", "福利", "活动",
        "立享", "笔笔", "0元购", "0元领", "免费领", "零元购",
        "邀请", "推广", "积分", "签到", "优惠券", "抽奖", "推荐",
        "限时", "抢购", "薪资", "月薪", "拼团", "砍价",
        "赠送", "中奖", "补贴", "新人专享", "热卖", "爆款", "种草",
        "帮砍", "直播间", "开播", "预告", "金币", "淘金币", "权益",
        "成长值", "会员日", "天天领", "摇一摇", "刮奖", "省钱卡",
        "面试", "简历", "职位", "hr", "boss直聘", "拉勾", "猎聘",
        "智联", "51job", "zhilian", "购物车", "收藏", "评价",
        "返现", "提现到账通知", "收益到账", "结算", "结算成功"
    )

    private val paymentActionKeywords = listOf(
        "支付成功", "支付", "消费", "付款成功", "付款", "扣款", "自动扣款", "代扣",
        "已支付", "刷卡", "花费", "已扣", "收款", "到账", "入账", "退款",
        "退回", "转入", "已退款", "退款成功", "转账", "代付"
    )

    private fun hasAmountSymbol(text: String): Boolean =
        text.contains("¥") || text.contains("￥") || text.contains("元")

    private fun hasPaymentAction(text: String): Boolean {
        val lower = text.lowercase()
        return paymentActionKeywords.any { lower.contains(it.lowercase()) }
    }

    private fun isPromotional(text: String): Boolean {
        val lower = text.lowercase()
        return promotionalBlacklist.any { lower.contains(it.lowercase()) }
    }

    /** 返回 true = 应当采集为候选交易；false = 放弃。 */
    fun shouldCapture(combined: String): Boolean {
        if (combined.isBlank()) return false
        if (!hasPaymentAction(combined)) return false
        if (!hasAmountSymbol(combined)) return false
        if (!completionWords.any { combined.contains(it) }) return false
        if (isPromotional(combined)) return false
        return true
    }
}
