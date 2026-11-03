package com.mudasir.smartledger.ml

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/** One explicitly reviewed transaction; the identity makes edits idempotent. */
data class HabitSample(
    val id: String, val type: String, val timestamp: Long, val amount: Double,
    val category: String, val channel: String, val merchant: String?
)

data class HabitSuggestion(val category: String, val support: Int, val share: Double)

/** Pure local model. The score is a similarity weight, not a probability. */
object HabitModel {
    private const val DAY_MS = 86_400_000.0

    fun upsert(samples: List<HabitSample>, sample: HabitSample): List<HabitSample> {
        if (sample.id.isBlank() || sample.category.isBlank() || !sample.amount.isFinite() || sample.amount <= 0) return samples
        return (samples.filterNot { it.id == sample.id } + sample).takeLast(2000)
    }

    fun updateReviewed(samples: List<HabitSample>, sample: HabitSample, requireExisting: Boolean): List<HabitSample> =
        if (requireExisting && samples.none { it.id == sample.id }) samples else upsert(samples, sample)

    fun suggest(samples: List<HabitSample>, query: HabitSample, now: Long): HabitSuggestion? {
        if (!query.amount.isFinite() || query.amount <= 0) return null
        val scores = mutableMapOf<String, Double>()
        val supports = mutableMapOf<String, Int>()
        val queryMinute = minuteOfDay(query.timestamp)
        for (sample in samples) {
            if (sample.id == query.id || sample.type != query.type || sample.category.isBlank() ||
                !sample.amount.isFinite() || sample.amount <= 0 || sample.timestamp > min(now, query.timestamp)) continue
            val diff = abs(minuteOfDay(sample.timestamp) - queryMinute)
            val hours = min(diff, 1440 - diff) / 60.0
            val amountDistance = abs(ln((sample.amount + 1.0) / (query.amount + 1.0)))
            val similarity = exp(-hours * hours / 0.5 - amountDistance * amountDistance / 0.5)
            if (similarity < 0.2) continue
            val ageDays = ((now - sample.timestamp) / DAY_MS).coerceAtLeast(0.0)
            val recency = exp(-ln(2.0) * ageDays / 90.0)
            val sameMerchant = !query.merchant.isNullOrBlank() &&
                query.merchant.trim().equals(sample.merchant?.trim(), ignoreCase = true)
            val merchantWeight = if (sameMerchant) 1.4 else 1.0
            val channelWeight = if (query.channel.isNotBlank() && query.channel == sample.channel) 1.05 else 1.0
            scores[sample.category] = (scores[sample.category] ?: 0.0) + similarity * recency * merchantWeight * channelWeight
            supports[sample.category] = (supports[sample.category] ?: 0) + 1
        }
        val ordered = scores.entries.sortedByDescending { it.value }
        val best = ordered.firstOrNull() ?: return null
        val total = ordered.sumOf { it.value }
        val support = supports[best.key] ?: 0
        val share = best.value / total
        // Require repeated, locally consistent evidence; abstain on ambiguous examples.
        if (support < 2 || best.value < 0.65 || share < 0.70) return null
        return HabitSuggestion(best.key, support, share)
    }

    private fun minuteOfDay(timestamp: Long): Int = Calendar.getInstance().run {
        timeInMillis = timestamp
        get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE)
    }
}
