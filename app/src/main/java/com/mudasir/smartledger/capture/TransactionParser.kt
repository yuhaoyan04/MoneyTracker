package com.mudasir.smartledger.capture

import com.mudasir.smartledger.data.TransactionRecord

/**
 * 交易信息解析器。
 *
 * 目标：把来自微信、支付宝、淘宝、京东、银行 App 的「支付/通知/短信」文本，
 * 解析为结构化交易（金额、收支方向、渠道、商户），确保「不遗漏每一条信息」。
 *
 * 策略：
 *  1) 用正则提取金额（兼容 ¥/￥/元，含逗号千分位与小数）。
 *  2) 用关键词判定收支方向（收入/支出）。
 *  3) 由来源（包名 / 银行短信签名）推断渠道。
 *
 * 解析失败返回 null —— 调用方据此决定是否仍以原文存入收件箱（兜底不遗漏）。
 */
object TransactionParser {

    // ---- 金额提取 ----
    // 匹配 ¥12.00 / ￥1,234.56 / 12.00元 / 1,234.5元
    private val transactionValue = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]{1,2})?"
    private val amountPatterns = listOf(
        Regex("[¥￥]\\s*($transactionValue)(?![0-9.,])"),
        Regex("(?<![0-9.,])($transactionValue)\\s*元")
    )
    private val labelledTransactionAmount = Regex(
        "(?<!余额)(?<!可用)(?:付款金额|支付金额|交易金额|消费金额|扣款金额|收款金额|到账金额|转入金额|实付金额|实付款|实付|金额)\\s*[:：]?\\s*(?:人民币|RMB)?\\s*[¥￥]?\\s*($transactionValue)(?![0-9.,])\\s*元?",
        RegexOption.IGNORE_CASE
    )
    private val movementAmount = Regex(
        "(?:已支付|已付款|支付|付款|消费|支出|扣款|扣费|扣除|刷卡|转出|转入|收入|入账|到账|收款|退款|存入|代发)(?:成功|完成|人民币|金额|款项|到账|\\s|[:：]){0,8}(?:[¥￥]\\s*($transactionValue)(?![0-9.,])|($transactionValue)\\s*元)",
        RegexOption.IGNORE_CASE
    )

    // ---- 收支方向关键词 ----
    // 强收入词：真实入账语义，营销文案极少伪造完整句式
    private val strongIncomeKeywords = listOf(
        "退款成功", "退款已", "已退款", "退款到账", "收款成功", "已收款",
        "转入成功", "已收到转账", "收款到账", "代发工资", "工资到账", "工资入账"
    )
    // 弱收入词：需配合强收入词或无支出词时才判收入
    private val incomeKeywords = listOf(
        "收款", "收入", "到账", "入账", "转入", "提现", "收益", "利息", "返还", "红包", "工资", "分红"
    )
    private val expenseKeywords = listOf(
        "支付成功", "消费", "付款", "扣款", "扣费", "扣除", "支出", "实付", "自动扣款", "代扣",
        "已支付", "付款成功", "刷卡", "购物", "充值", "花费", "花呗", "已扣", "转出", "汇出", "支取", "取现"
    )
    // 营销话术：与支出词同现时视为营销噪音（真实扣款通知通常不含这些）
    private val marketingKeywords = listOf(
        "立享", "领取", "邀请", "免费领", "0元领", "零元领", "秒杀", "砍价",
        "扫码领", "开通即", "快去", "速抢", "拼团", "助力"
    )

    // ---- 渠道推断 ----
    private val packageChannelMap = mapOf(
        "com.tencent.mm" to "微信支付",
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.eg.android.AlipayGphone.rc" to "支付宝",
        "com.eg.android.AlipayGphone.lite" to "支付宝",
        "com.taobao.taobao" to "淘宝",
        "com.taobao.idlefish" to "闲鱼",
        "com.jingdong.app.mall" to "京东",
        "com.jingdong.app.mall.alpha" to "京东",
        "com.jd.jrapp" to "京东",
        "com.jdpaysdk" to "京东支付",
        "com.sankuai.meituan" to "美团",
        "com.sankuai.meituan.takeoutnew" to "美团",
        "com.meituan.retail.v4" to "美团",
        "com.sankuai.mt.pro" to "美团",
        "com.ele.android" to "饿了么",
        "com.sdu.didi.psnger" to "滴滴",
        "com.xunmeng.pinduoduo" to "拼多多",
        "com.unionpay" to "云闪付",
        "com.MobileTicket" to "12306",
        "com.taou.maimai" to "脉脉",
        "com.mobile.me" to "其他"
    )

    private val bankKeywords = listOf("银行", "信用社", "储蓄", "招商", "工商", "建设", "农业", "中国", "交通", "邮储", "民生", "浦发", "兴业", "光大", "华夏", "平安", "中信", "广发")

    /** 主入口：解析一段文本。channelHint 用于 SMS 的渠道推断。 */
    fun parse(
        text: String,
        source: String,
        packageName: String? = null,
        channelHint: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ): ParsedTransaction? {
        val clean = text.replace("\r", " ").replace("\n", " ").trim()
        if (clean.isBlank()) return null

        val amount = extractAmount(clean) ?: return null
        if (amount <= 0.0) return null

        val type = inferType(clean)
        val channel = channelHint ?: inferChannel(clean, packageName)
        val merchant = inferMerchant(clean)

        return ParsedTransaction(
            type = type,
            amount = amount,
            merchant = merchant,
            channelName = channel,
            paymentMethod = inferPaymentMethod(clean),
            rawText = text,
            source = source,
            packageName = packageName,
            timestamp = timestamp
        )
    }

    fun channelForPackage(pkg: String?): String? = pkg?.let {
        packageChannelMap[it] ?: if (it in CaptureWhitelist.bankPackages) "银行卡" else null
    }

    private fun extractAmount(text: String): Double? {
        fun value(raw: String): Double? = raw.replace(",", "").toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
        val labelled = labelledTransactionAmount.findAll(text).mapNotNull { value(it.groupValues[1]) }.toSet()
        val moved = movementAmount.findAll(text).mapNotNull { m -> value(m.groupValues[1].ifEmpty { m.groupValues[2] }) }.toSet()
        val explicit = labelled + moved
        if (explicit.isNotEmpty()) return explicit.singleOrNull()
        val unrelated = Regex("(?:余额|可用余额|可用额度|剩余额度|授信额度|优惠|补贴|满减|立减)\\s*[:：]?\\s*[¥￥]?\\s*$")
        val fallback = amountPatterns.asSequence().flatMap { it.findAll(text) }
            .filterNot { unrelated.containsMatchIn(text.substring(0, it.range.first).takeLast(20)) }
            .mapNotNull { value(it.groupValues[1]) }.toSet()
        return fallback.singleOrNull()
    }

    fun inferType(text: String): String {
        val t = text.replace(" ", "")
        val hasStrongIncome = strongIncomeKeywords.any { t.contains(it) }
        val hasWeakIncome = incomeKeywords.any { t.contains(it) }
        val hasExpense = expenseKeywords.any { t.contains(it) }
        val marketing = marketingKeywords.any { t.contains(it) }
        return when {
            // 强收入词（退款/工资/收款成功等）→ 收入；营销词不改变其真实性
            hasStrongIncome -> TransactionRecord.TYPE_INCOME
            // 支出词存在：
            // - 同时有弱收入词且为营销话术（如「支付XX元立享优惠，即刻到账」）→ 支出
            // - 无营销话术（「支付成功，已优惠5元」）→ 支出
            hasExpense -> TransactionRecord.TYPE_EXPENSE
            // 仅弱收入词（到账/入账/提现等）→ 收入（如「您的退款已到账」）
            hasWeakIncome && !marketing -> TransactionRecord.TYPE_INCOME
            // 弱收入词 + 营销话术 → 疑似营销噪音，按支出兜底（收件箱可修正）
            else -> TransactionRecord.TYPE_EXPENSE
        }
    }

    private fun inferChannel(text: String, packageName: String?): String {
        channelForPackage(packageName)?.let { return it }
        val t = text.replace(" ", "")
        if (t.contains("微信")) return "微信支付"
        if (t.contains("支付宝") || t.contains("花呗") || t.contains("蚂蚁")) return "支付宝"
        if (t.contains("京东")) return "京东"
        if (t.contains("淘宝") || t.contains("天猫")) return "淘宝"
        if (bankKeywords.any { t.contains(it) } || t.contains("尾号")) return "银行卡"
        return "其他"
    }

    private val knownChannelNames = setOf(
        "微信支付", "微信", "支付宝", "京东", "淘宝", "天猫", "银行卡", "银行",
        "信用卡", "花呗", "借呗", "云闪付", "数字人民币", "现金", "其他", "android"
    )

    private fun inferMerchant(text: String): String? {
        listOf(
            Regex("在\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{2,20}?)\\s*(消费|付款|支付|购物|买单)"),
            Regex("于\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{2,20}?)\\s*(消费|付款|支付|购物)"),
            Regex("向\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{2,20}?)\\s*(转账|付款|支付)"),
            Regex("至\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{2,20}?)\\s*(转账|付款|支付)"),
            // v2.36.0：对齐真实通知文案 —— 商户记忆层（最快的学习层）依赖商户名，
            // 此前提取模式覆盖不了微信/支付宝实际推送格式，商户几乎永远为 null，
            // 导致自进化无感。新增：转账给/已转账给/转给/付给/你在X支付/「X」
            Regex("已转账给\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{2,20})"),
            Regex("转账给\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{2,20})"),
            Regex("转给\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{2,20})"),
            Regex("付给\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{2,20})"),
            Regex("你在\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{2,20}?)\\s*(支付|消费|付款)"),
            Regex("「([\\u4e00-\\u9fa5A-Za-z0-9]{2,20}?)」"),
            Regex("商户[:：\\s]*([\\u4e00-\\u9fa5A-Za-z0-9]{2,20})"),
            Regex("收款方[:：\\s]*([\\u4e00-\\u9fa5A-Za-z0-9]{2,20})"),
            Regex("付款方[:：\\s]*([\\u4e00-\\u9fa5A-Za-z0-9]{2,20})"),
            Regex("对方[:：\\s]*([\\u4e00-\\u9fa5A-Za-z0-9]{2,20})"),
            Regex("【([\\u4e00-\\u9fa5A-Za-z0-9]{2,20}?)】"),
            Regex("《([\\u4e00-\\u9fa5A-Za-z0-9]{2,20}?)》")
        ).forEach { pattern ->
            pattern.find(text)?.groupValues?.getOrNull(1)?.let { if (it.isNotBlank()) return it }
        }

        // 尝试从通知标题段（第一个 | 之前）提取商户名
        val firstSegment = text.substringBefore(" | ").trim()
        if (firstSegment.length in 2..20) {
            val lower = firstSegment.lowercase()
            if (knownChannelNames.none { lower.contains(it.lowercase()) }) {
                return firstSegment
            }
        }
        return null
    }

    private fun inferPaymentMethod(text: String): String? {
        val t = text.replace(" ", "")
        return when {
            t.contains("花呗") -> "花呗"
            t.contains("信用卡") -> "信用卡"
            t.contains("储蓄卡") || t.contains("借记卡") -> "储蓄卡"
            t.contains("余额宝") -> "余额宝"
            t.contains("余额") || t.contains("零钱") -> "余额"
            t.contains("理财") -> "理财"
            else -> null
        }
    }
}
