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
    fun exceedsThreshold(amount: Double, threshold: BigDecimal): Boolean =
        amount.isFinite() && threshold >= BigDecimal.ZERO && BigDecimal(amount.toString()) > threshold

    private val actions = listOf("提交订单", "确认付款", "立即付款", "继续付款", "确认支付", "立即支付", "去付款", "去支付", "支付订单", "订单支付", "确认并支付")
    private val orderListTitles = setOf("全部订单", "我的订单", "订单列表", "历史订单", "订单记录")
    private val orderListTabs = setOf("待付款", "待发货", "待收货", "待评价", "已完成")
    private val outcomes = listOf("支付成功", "付款成功", "交易成功", "扣款成功", "支付失败", "付款失败", "交易失败", "订单已取消", "退款成功")
    // Generic subtotals may precede discounts or shipping, so they are not proof of the final payable amount.
    private val labels = "(?:实付款|实付金额|实际支付|实际付款|应付金额|应付款|应付|需付款|需付|支付金额|付款金额)"
    private val labelOnly = Regex("^\\s*$labels\\s*[:：]?\\s*$")
    private val amount = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]{1,2})?"
    private val labelledAmount = Regex("(?:^|\\s|[：:])$labels\\s*[:：]?\\s*[¥￥]?\\s*($amount)(?![0-9.])\\s*元?")
    private val amountOnly = Regex("^\\s*[¥￥]?\\s*($amount)\\s*元?\\s*$")
    // Only accept an amount embedded in the payment action itself, not a nearby subtotal.
    private val actionAmount = Regex("^\\s*(?:确认付款|立即付款|确认支付|立即支付|确认并支付)\\s*[:：]?\\s*[¥￥]\\s*($amount)\\s*元?\\s*$")

    /** Aggregate evidence only. Never returns page text, amounts, or merchant information. */
    fun diagnosticSummary(nodes: List<CheckoutNode>): String {
        val actionNodes = nodes.count { node -> node.text.trim().let { it.length <= 32 && actions.any(it::contains) } }
        val labelled = nodes.count { labelledAmount.containsMatchIn(it.text) || labelOnly.matches(it.text) }
        val split = nodes.count { labelOnly.matches(it.text) }
        val subtotal = nodes.count { Regex("合计|总计|小计").containsMatchIn(it.text) }
        val buttonAmount = nodes.count { actionAmount.matches(it.text) }
        val bareAmounts = nodes.count { amountOnly.matches(it.text) }
        val nextNumeric = nodes.indices.count { index ->
            labelOnly.matches(nodes[index].text) && nodes.getOrNull(index + 1)?.let { next ->
                amountOnly.matches(next.text) || next.text.trim() in setOf("¥", "￥")
            } == true
        }
        val sameParent = nodes.indices.count { index ->
            labelOnly.matches(nodes[index].text) && nodes.getOrNull(index + 1)?.let { next ->
                (amountOnly.matches(next.text) || next.text.trim() in setOf("¥", "￥")) &&
                    next.parent == nodes[index].parent && nearby(nodes[index], next)
            } == true
        }
        val orderTitle = nodes.count { it.text.trim() == "确认订单" }
        val listTitle = nodes.any { it.text.trim() in orderListTitles }
        val listTabs = nodes.map { it.text.trim() }.toSet().intersect(orderListTabs).size
        val result = nodes.any { node -> outcomes.any(node.text::contains) }
        return "节点=${nodes.size}, 操作=$actionNodes, 应付标签=$labelled, 标签独立=$split, 合计标签=$subtotal, 按钮含金额=$buttonAmount, 裸金额=$bareAmounts, 标签后数字=$nextNumeric, 同层邻近=$sameParent, 确认订单标题=$orderTitle, 订单列表=$listTitle, 列表标签=$listTabs, 结果页=$result"
    }

    fun analyze(pkg: String, nodes: List<CheckoutNode>, threshold: BigDecimal): CheckoutResult {
        if (!isSupported(pkg)) return CheckoutResult(CheckoutResult.Status.NOT_CHECKOUT)
        if (nodes.isEmpty()) return CheckoutResult(CheckoutResult.Status.NO_ACCESSIBLE_TEXT)
        val text = nodes.joinToString(" ") { it.text }
        val hasAction = nodes.any { node ->
            val value = node.text.trim()
            value.length <= 32 && actions.any(value::contains)
        }
        if (outcomes.any(text::contains) || !hasAction) return CheckoutResult(CheckoutResult.Status.NOT_CHECKOUT)
        // Order-history pages can show multiple independent totals and pay buttons. They are
        // not a single active checkout and must not trigger a full-screen interruption.
        if (nodes.any { it.text.trim() in orderListTitles }) return CheckoutResult(CheckoutResult.Status.NOT_CHECKOUT)
        if (nodes.map { it.text.trim() }.toSet().intersect(orderListTabs).size >= 2) return CheckoutResult(CheckoutResult.Status.NOT_CHECKOUT)
        val amounts = linkedSetOf<BigDecimal>()
        nodes.forEachIndexed { index, node ->
            labelledAmount.findAll(node.text).forEach { match -> parse(match.groupValues[1])?.let(amounts::add) }
            actionAmount.matchEntire(node.text)?.let { parse(it.groupValues[1])?.let(amounts::add) }
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
