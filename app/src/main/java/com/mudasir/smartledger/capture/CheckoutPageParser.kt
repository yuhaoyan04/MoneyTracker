package com.mudasir.smartledger.capture

import java.math.BigDecimal

/** A bounded snapshot of visible accessibility nodes. Text is processed in memory only. */
data class CheckoutNode(val text: String, val parent: Int, val left: Int, val top: Int, val right: Int, val bottom: Int)

data class CheckoutResult(val status: Status, val amount: BigDecimal? = null) {
    enum class Status { NOT_CHECKOUT, NO_ACCESSIBLE_TEXT, NO_AMOUNT, AMBIGUOUS, BELOW_THRESHOLD, QUALIFIED }
}

object CheckoutPageParser {
    val shoppingPackages = setOf("com.taobao.taobao", "com.jingdong.app.mall", "com.xunmeng.pinduoduo",
        "com.sankuai.meituan", "com.sankuai.meituan.takeoutnew", "com.MobileTicket")
    fun isSupported(pkg: String): Boolean = pkg in shoppingPackages || CaptureWhitelist.isPageWatched(pkg)
    fun exceedsThreshold(amount: Double, threshold: Float): Boolean =
        amount.isFinite() && threshold.isFinite() && threshold >= 0f &&
            BigDecimal(amount.toString()) > BigDecimal(threshold.toString())

    private val actions = listOf("提交订单", "确认订单", "确认付款", "立即付款", "确认支付", "立即支付", "去付款", "去支付", "支付订单", "订单支付", "确认并支付")
    private val outcomes = listOf("支付成功", "付款成功", "交易成功", "扣款成功", "支付失败", "付款失败", "交易失败", "订单已取消", "退款成功")
    private val labels = "(?:实付款|实付金额|实际支付|实际付款|应付金额|应付款|应付|需付款|需付|支付金额|付款金额|合计金额|合计|总计)"
    private val labelOnly = Regex("^\\s*$labels\\s*[:：]?\\s*$")
    private val amount = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]{1,2})?"
    private val labelledAmount = Regex("(?:^|\\s|[：:])$labels\\s*[:：]?\\s*[¥￥]?\\s*($amount)(?![0-9.])\\s*元?")
    private val amountOnly = Regex("^\\s*[¥￥]?\\s*($amount)\\s*元?\\s*$")

    fun analyze(pkg: String, nodes: List<CheckoutNode>, threshold: BigDecimal): CheckoutResult {
        if (!isSupported(pkg)) return CheckoutResult(CheckoutResult.Status.NOT_CHECKOUT)
        if (nodes.isEmpty()) return CheckoutResult(CheckoutResult.Status.NO_ACCESSIBLE_TEXT)
        val text = nodes.joinToString(" ") { it.text }
        if (outcomes.any(text::contains) || actions.none(text::contains)) return CheckoutResult(CheckoutResult.Status.NOT_CHECKOUT)
        val amounts = linkedSetOf<BigDecimal>()
        nodes.forEachIndexed { index, node ->
            labelledAmount.findAll(node.text).forEach { match -> parse(match.groupValues[1])?.let(amounts::add) }
            if (labelOnly.matches(node.text)) {
                // Only the immediately following sibling can supply a split label's amount.
                val next = nodes.getOrNull(index + 1)
                if (next != null && next.parent == node.parent && nearby(node, next)) {
                    amountOnly.matchEntire(next.text)?.let { parse(it.groupValues[1])?.let(amounts::add) }
                    if (next.text.trim() in setOf("¥", "￥")) {
                        val value = nodes.getOrNull(index + 2)
                        if (value != null && value.parent == node.parent && nearby(node, value)) {
                            amountOnly.matchEntire(value.text)?.let { parse(it.groupValues[1])?.let(amounts::add) }
                        }
                    }
                }
            }
        }
        if (amounts.isEmpty()) return CheckoutResult(CheckoutResult.Status.NO_AMOUNT)
        if (amounts.size != 1) return CheckoutResult(CheckoutResult.Status.AMBIGUOUS)
        val value = amounts.single()
        return CheckoutResult(if (value > threshold) CheckoutResult.Status.QUALIFIED else CheckoutResult.Status.BELOW_THRESHOLD, value)
    }

    private fun parse(raw: String): BigDecimal? = runCatching { BigDecimal(raw.replace(",", "")).stripTrailingZeros() }
        .getOrNull()?.takeIf { it > BigDecimal.ZERO && it <= BigDecimal("10000000") }

    private fun nearby(a: CheckoutNode, b: CheckoutNode): Boolean {
        val height = maxOf(a.bottom - a.top, b.bottom - b.top, 1)
        return kotlin.math.abs(a.top - b.top) <= height * 2 && b.left >= a.left - height * 2
    }
}
