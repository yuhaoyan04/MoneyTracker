package com.mudasir.smartledger.util

import com.mudasir.smartledger.data.TransactionRecord

/** The suggestion score is a heuristic, not a calibrated probability. */
object CaptureReviewPolicy {
    fun canConfirm(amount: Double): Boolean = amount.isFinite() && amount > 0.0
    fun canAutoConfirm(amount: Double, category: String, score: Float?): Boolean =
        canConfirm(amount) && category.isNotBlank() && score != null && score.isFinite() && score >= 0.5f

    /** A hint for human review only; equal amounts cannot prove that transactions are identical. */
    fun possiblyDuplicateIds(records: List<TransactionRecord>): Set<Long> {
        val result = mutableSetOf<Long>()
        for (i in records.indices) for (j in i + 1 until records.size) {
            val a = records[i]; val b = records[j]
            if (a.type == b.type && canConfirm(a.amount) && canConfirm(b.amount) &&
                kotlin.math.abs(a.amount - b.amount) < 0.005 &&
                kotlin.math.abs(a.timestamp - b.timestamp) <= 300_000L &&
                (a.source != b.source || a.packageName != b.packageName)) {
                result.add(a.id); result.add(b.id)
            }
        }
        return result
    }
}
