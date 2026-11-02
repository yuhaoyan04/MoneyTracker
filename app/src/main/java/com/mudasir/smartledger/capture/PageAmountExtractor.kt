package com.mudasir.smartledger.capture

/**
 * 支付页面金额提取器（纯逻辑，可单元测试）。
 *
 * v2.37.0：支付宝/银行卡通知栏不显示金额，页面抓取成为金额主通道。
 * 真实页面金额的三种渲染形态：
 *   1) ¥1,234.56 / ￥200.00      —— 符号前缀（含千分位逗号！银行大额常带逗号）
 *   2) 156.00元 / 1,000.00元     —— 元后缀
 *   3) 200.00                    —— 独立文本节点（¥ 缺失，H5 收银台常见），
 *      仅在强确认词命中后作为兜底，分期页不适用（多个等额小数会取错）
 */
object PageAmountExtractor {

    private val installmentWords = listOf("每月", "分期", "期数", "月供")

    // v2.37.0 修复：[0-9]+ 不含逗号 → 「¥1,234.56」只解析出 1.00（银行大额全错）
    private val amountRegex = Regex("""[¥￥]\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val yuanRegex = Regex("""([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*元""")
    private val bareDecimalRegex = Regex("""([0-9][0-9,]*\.[0-9]{2})""")

    private fun parseAmount(raw: String): Double? =
        raw.replace(",", "").toDoubleOrNull()?.takeIf { it > 0 }

    /** Accept a labelled transaction amount or one unambiguous currency amount. */
    fun extractConservative(text: String): Double? {
        val label = Regex("""(?:实付金额|付款金额|支付金额|交易金额|实付款|实付|付款|支付)\s*[:：]?\s*[¥￥]?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s*元?""")
        val labelled = label.findAll(text).mapNotNull { parseAmount(it.groupValues[1]) }.distinct().toList()
        if (labelled.size == 1) return labelled.single()
        if (labelled.size > 1) return null
        val amounts = (amountRegex.findAll(text).mapNotNull { parseAmount(it.groupValues[1]) } +
            yuanRegex.findAll(text).mapNotNull { parseAmount(it.groupValues[1]) }).distinct().toList()
        return amounts.singleOrNull()
    }

    /**
     * 从页面文本中提取支付金额。
     * @param allowBareFallback 无符号裸小数兜底开关（分期页传 false 或自动检测）
     */
    fun extract(text: String, allowBareFallback: Boolean = true): Double? {
        amountRegex.find(text)?.let { return parseAmount(it.groupValues[1]) }
        yuanRegex.find(text)?.let { return parseAmount(it.groupValues[1]) }
        if (!allowBareFallback) return null
        if (installmentWords.any { text.contains(it) }) return null
        return bareDecimalRegex.findAll(text)
            .mapNotNull { parseAmount(it.groupValues[1]) }
            .maxOrNull()
    }
}
