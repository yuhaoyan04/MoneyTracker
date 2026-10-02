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
    private val amountPatterns = listOf(
        Regex("[¥￥]\\s*([0-9][0-9,]*\\.?[0-9]{0,2})"),
        Regex("([0-9][0-9,]*\\.?[0-9]{2})\\s*元"),
        Regex("金额[:：\\s]*([0-9][0-9,]*\\.?[0-9]{0,2})"),
        Regex("实付[:：\\s]*([0-9][0-9,]*\\.?[0-9]{0,2})")
    )

    // ---- 收支方向关键词 ----
    private val incomeKeywords = listOf(
        "收款", "收入", "到账", "入账", "代发工资", "退款", "退回", "转入", "提现",
        "红包", "转账收入", "收益", "利息", "返还", "退款成功", "已退款"
    )
    private val expenseKeywords = listOf(
        "支付成功", "消费", "付款", "扣款", "支出", "实付", "自动扣款", "代扣",
        "已支付", "付款成功", "刷卡", "购物", "充值", "花费", "花呗", "已扣"
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

    fun channelForPackage(pkg: String?): String? = pkg?.let { packageChannelMap[it] }

    private fun extractAmount(text: String): Double? {
        for (pattern in amountPatterns) {
            val m = pattern.find(text) ?: continue
            val raw = m.groupValues.getOrNull(1)?.replace(",", "") ?: continue
            val value = raw.toDoubleOrNull() ?: continue
            if (value > 0.0) return value
        }
        return null
    }

    private fun inferType(text: String): String {
        val t = text.replace(" ", "")
        // 收入关键词优先（避免「退款」被「消费」误判等）
        if (incomeKeywords.any { kw -> t.contains(kw) }) return TransactionRecord.TYPE_INCOME
        if (expenseKeywords.any { kw -> t.contains(kw) }) return TransactionRecord.TYPE_EXPENSE
        // 默认按金额上下文：含「余额」「账户」偏向银行，默认支出
        return TransactionRecord.TYPE_EXPENSE
    }

    private fun inferChannel(text: String, packageName: String?): String {
        packageChannelMap[packageName]?.let { return it }
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
            t.contains("余额") || t.contains("零钱") -> "余额"
            t.contains("余额宝") -> "余额宝"
            t.contains("理财") -> "理财"
            else -> null
        }
    }
}
