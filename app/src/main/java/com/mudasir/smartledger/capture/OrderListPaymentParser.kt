package com.mudasir.smartledger.capture

import java.math.BigDecimal

/** Retains only a button rectangle and its uniquely labelled payable amount. */
data class OrderPayCandidate(val left: Int, val top: Int, val right: Int, val bottom: Int, val amount: BigDecimal)

object OrderListPaymentParser {
    fun candidates(nodes: List<CheckoutNode>, width: Int, height: Int): List<OrderPayCandidate> {
        if (width <= 0 || height <= 0 || !CheckoutPageParser.isTaobaoOrderList(VisualGuardTargets.TAOBAO, nodes)) return emptyList()
        val statuses = nodes.filter { it.text.trim() == "待付款" && it.left >= width / 2 && it.bottom > it.top }
        val buttons = mutableListOf<CheckoutNode>()
        nodes.filter { it.text.trim() == "继续付款" && it.right > it.left && it.bottom > it.top }
            .sortedByDescending { (it.right - it.left).toLong() * (it.bottom - it.top) }
            .forEach { candidate ->
                if (buttons.none { overlaps(it, candidate) }) buttons.add(candidate)
            }
        return buttons.mapNotNull { button ->
            val status = statuses.filter { it.top < button.top && button.top - it.top <= height / 2 }
                .maxByOrNull { it.top } ?: return@mapNotNull null
            if (buttons.any { it !== button && it.top in status.top until button.top }) return@mapNotNull null
            val group = nodes.filter { it.top >= status.top && it.bottom <= button.bottom && it.bottom > it.top }
            val result = CheckoutPageParser.analyze(VisualGuardTargets.TAOBAO, group, BigDecimal.ZERO)
            val value = result.amount
            if (result.status != CheckoutResult.Status.QUALIFIED || value == null) null
            else OrderPayCandidate(button.left, button.top, button.right, button.bottom, value)
        }
    }

    fun match(candidates: List<OrderPayCandidate>, left: Int, top: Int, right: Int, bottom: Int): OrderPayCandidate? {
        if (right <= left || bottom <= top) return null
        val x = (left + right) / 2; val y = (top + bottom) / 2
        return candidates.filter { x in it.left..it.right && y in it.top..it.bottom }.singleOrNull()
    }

    private fun overlaps(a: CheckoutNode, b: CheckoutNode): Boolean =
        (a.left + a.right) / 2 in b.left..b.right && (a.top + a.bottom) / 2 in b.top..b.bottom ||
            (b.left + b.right) / 2 in a.left..a.right && (b.top + b.bottom) / 2 in a.top..a.bottom
}
