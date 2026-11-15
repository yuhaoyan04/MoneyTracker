package com.mudasir.smartledger.util

import com.mudasir.smartledger.data.ExpenseTaxonomy
import com.mudasir.smartledger.data.IncomeTaxonomy

/** One category keeps its color across months, ranking changes and exported reports. */
object ChartPalette {
    val fallbackHex = listOf(
        "#467D6B", "#6B8CAE", "#9C6B8F", "#94A374", "#CE9B4E", "#C07A8A",
        "#8C9BAB", "#55917F", "#6D7BB5", "#A8A29A", "#B08968", "#7F9BA6"
    )
    private val canonical = (ExpenseTaxonomy.groups + IncomeTaxonomy.groups)
        .associate { it.name to it.color }
    private val legacy = mapOf("网购" to "#9C6B8F", "红包" to "#C07A8A")

    fun hexFor(category: String): String = canonical[category] ?: legacy[category]
        ?: fallbackHex[(category.hashCode() and Int.MAX_VALUE) % fallbackHex.size]
}
