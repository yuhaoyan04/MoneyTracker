package com.mudasir.smartledger.capture

import java.math.BigDecimal

/** OCR data is used in memory only. Bounds are in screenshot pixels. */
data class VisualText(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

object VisualCheckoutParser {
    private val action = Regex("继续付款|确认付款|立即付款|确认支付|立即支付|去付款|去支付|提交订单|确认下单|提交并支付")
    private val outcome = Regex("支付成功|付款成功|支付失败|付款失败|订单已取消")
    private val label = Regex("实付款|实付金额|实际支付|实际付款|应付金额|应付款|应付|需付款|需付|支付金额|付款金额")
    private val amount = Regex("(?<![0-9.,])(?:[¥￥]\\s*)?([0-9]{1,6}(?:\\.[0-9]{1,2})?)(?![0-9.,])")
    // Only a price inside the actual pay button, never nearby suggested item prices or "0元下单".
    private val actionAmount = Regex("^(?:立即支付|确认支付|立即付款|确认付款|确认并支付)[:：]?[¥￥]([0-9]{1,6}(?:\\.[0-9]{1,2})?)$")
    private val payButtonOnly = Regex("^(?:立即支付|确认支付|立即付款|确认付款|确认并支付)$")
    private val currencyAmountOnly = Regex("^[¥￥]([0-9]{1,6}(?:\\.[0-9]{1,2})?)$")
    private val exclusions = Regex("订单号|交易号|编号|优惠|立减|红包|折扣|运费|邮费|原价|小计|合计|总计")
    private val listTitles = listOf("全部订单", "我的订单", "订单列表", "历史订单", "订单记录")
    private val listTabs = listOf("待付款", "待发货", "待收货", "待评价", "已完成")

    /** SKU selection is not a checkout; require explicit order and payable labels. */
    fun amountForTaobaoSku(lines: List<VisualText>, width: Int, height: Int): BigDecimal? {
        val text = lines.joinToString(" ") { it.text.replace(Regex("\\s+"), "") }
        if (listOf("确认订单", "订单确认", "提交订单").none(text::contains) || !label.containsMatchIn(text)) return null
        return amount(lines, width, height)
    }

    /** A payment label is mandatory; arbitrary small numbers are never treated as amounts. */
    fun amount(lines: List<VisualText>, width: Int, height: Int): BigDecimal? {
        if (width <= 0 || height <= 0 || lines.isEmpty()) return null
        val normalized = lines.map { it.copy(text = it.text.replace(Regex("\\s+"), "")) }
        if (normalized.any { outcome.containsMatchIn(it.text) }) return null
        if (normalized.any { line -> listTitles.any(line.text::contains) } ||
            listTabs.count { tab -> normalized.any { tab in it.text } } >= 2) return null
        if (normalized.none { action.containsMatchIn(it.text) && it.top >= height / 2 }) return null
        val candidates = mutableListOf<BigDecimal>()
        val buttonAmounts = normalized.asSequence().filter { it.top >= height / 2 }
            .flatMap { button ->
                val direct = actionAmount.matchEntire(button.text)?.groupValues?.get(1)?.let(::parse)
                if (direct != null) sequenceOf(direct)
                else if (payButtonOnly.matches(button.text)) {
                    val h = (button.bottom - button.top).coerceAtLeast(1)
                    normalized.asSequence().filter { it !== button && it.left >= button.right - h / 2 &&
                        it.left - button.right <= width / 5 && kotlin.math.abs(it.top - button.top) <= h }
                        .mapNotNull { currencyAmountOnly.matchEntire(it.text)?.groupValues?.get(1)?.let(::parse) }
                } else emptySequence()
            }.toSet()
        // Multiple different payment-button amounts are ambiguous, rather than selecting a product price.
        if (buttonAmounts.size > 1) return null
        if (buttonAmounts.size == 1) return buttonAmounts.single()
        normalized.forEach { line ->
            val match = label.find(line.text) ?: return@forEach
            if (exclusions.containsMatchIn(line.text.substring(0, match.range.first))) return@forEach
            val tail = line.text.substring(match.range.last + 1)
            val explicit = Regex("^[:：]?[¥￥]?([0-9]{1,6}(?:\\.[0-9]{1,2})?)(?![0-9.,])").find(tail)
            explicit?.groupValues?.get(1)?.let { parse(it)?.let(candidates::add) }
            if (explicit == null && tail.trim(':', '：', '¥', '￥').isEmpty()) {
                val h = (line.bottom - line.top).coerceAtLeast(1)
                normalized.asSequence().filter { it !== line && it.left >= line.right - h &&
                    it.left - line.right <= width / 3 && kotlin.math.abs(it.top - line.top) <= h * 2 &&
                    !exclusions.containsMatchIn(it.text) }
                    .mapNotNull { next -> amount.matchEntire(next.text)?.groupValues?.get(1)?.let(::parse) }
                    .forEach(candidates::add)
            }
        }
        return candidates.minOrNull()
    }

    private fun parse(s: String): BigDecimal? = runCatching { BigDecimal(s) }.getOrNull()
        ?.takeIf { it > BigDecimal.ZERO && it <= BigDecimal("1000000") }
}
