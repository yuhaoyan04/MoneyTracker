package com.mudasir.smartledger.capture

import java.math.BigDecimal

/** OCR data is used in memory only. Bounds are in screenshot pixels. */
data class VisualText(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

object VisualCheckoutParser {
    /** Conservative experiment for the observed JD cashier: one centered currency
     * amount, a WeChat choice and a bottom confirmation action are all required. */
    enum class JdCashierStatus { NO_PAGE, OUTCOME, NO_WECHAT, NO_ACTION, NO_AMOUNT, MULTIPLE_AMOUNTS, MATCHED }
    data class JdCashierResult(val status: JdCashierStatus, val amount: BigDecimal? = null)
    /** Two different recognizers must agree on every digit at the same prominent
     * centered location; this does not claim the price cannot change later. */
    fun jdCrossCheckedPrices(full: List<VisualText>, latin: List<VisualText>, width: Int, height: Int): Set<BigDecimal> {
        if (width <= 0 || height <= 0) return emptySet()
        val pattern = Regex("^[¥￥YyVv]?([0-9]{1,6})[.．]([0-9]{2})$")
        val excluded = Regex("订单|编号|尾号|优惠|立减|分期|原价|运费|邮费|红包")
        return latin.asSequence().filter { line ->
            line.top in (height / 10)..(height * 22 / 100) &&
                (line.left + line.right) / 2 in (width * 3 / 10)..(width * 7 / 10) &&
                line.bottom - line.top >= height / 50
        }.mapNotNull { line ->
            val normalized = line.text.replace(Regex("\\s+"), "")
            val match = pattern.matchEntire(normalized) ?: return@mapNotNull null
            val digits = match.groupValues[1] + match.groupValues[2]
            val aligned = full.any { other ->
                !excluded.containsMatchIn(other.text) &&
                    kotlin.math.abs((other.left + other.right) - (line.left + line.right)) <= width / 12 &&
                    kotlin.math.abs(other.top - line.top) <= height / 50 &&
                    other.bottom - other.top >= height / 50 &&
                    other.text.filter { it in '0'..'9' } == digits
            }
            if (aligned) parse(match.groupValues[1] + "." + match.groupValues[2]) else null
        }.toSet()
    }
    fun inspectJdCashier(lines: List<VisualText>, width: Int, height: Int, priceCropText: String? = null,
                         latinPriceCropText: String? = null, latinScreenLines: List<VisualText> = emptyList()): JdCashierResult {
        if (width <= 0 || height <= 0 || lines.isEmpty()) return JdCashierResult(JdCashierStatus.NO_PAGE)
        val normalized = lines.map { it.copy(text = it.text.replace(Regex("\\s+"), "").replace('．', '.')) }
        if (normalized.any { outcome.containsMatchIn(it.text) }) return JdCashierResult(JdCashierStatus.OUTCOME)
        if (normalized.none { it.text.contains("微信支付") }) return JdCashierResult(JdCashierStatus.NO_WECHAT)
        if (normalized.none { it.top >= height * 2 / 3 && it.text == "确认付款" }) return JdCashierResult(JdCashierStatus.NO_ACTION)
        val pattern = Regex("^[¥￥]([0-9]{1,6}\\.[0-9]{2})$")
        val fullValues = normalized.filter { it.top in (height / 20)..(height * 3 / 10) &&
            (it.left + it.right) / 2 in (width / 4)..(width * 3 / 4) }
            .mapNotNull { pattern.matchEntire(it.text)?.groupValues?.get(1)?.let(::parse) }
            .toSet()
        // A magnified top-center crop may recover tiny price digits. A bare number
        // requires matching recognizers and matching price geometry below.
        val cropPattern = Regex("(?<![0-9.,．])[¥￥]\\s*([0-9]{1,6})\\s*[.．]\\s*([0-9])\\s*([0-9])(?![0-9.,．])")
        fun valuesInCrop(text: String?): Set<BigDecimal> = text?.let { crop -> cropPattern.findAll(crop).mapNotNull {
            parse(it.groupValues[1] + "." + it.groupValues[2] + it.groupValues[3])
        }.toSet() }.orEmpty()
        val values = fullValues + valuesInCrop(priceCropText) + valuesInCrop(latinPriceCropText) +
            jdCrossCheckedPrices(normalized, latinScreenLines, width, height)
        return when (values.size) {
            0 -> JdCashierResult(JdCashierStatus.NO_AMOUNT)
            1 -> JdCashierResult(JdCashierStatus.MATCHED, values.single())
            else -> JdCashierResult(JdCashierStatus.MULTIPLE_AMOUNTS)
        }
    }
    fun amountForJdCashier(lines: List<VisualText>, width: Int, height: Int): BigDecimal? = inspectJdCashier(lines, width, height).amount
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
