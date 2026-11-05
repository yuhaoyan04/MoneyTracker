package com.mudasir.smartledger

import com.mudasir.smartledger.util.CaptureReviewPolicy
import com.mudasir.smartledger.data.TransactionRecord
import org.junit.Assert.*
import org.junit.Test

class CaptureReviewPolicyTest {
    @Test fun confirmationRequiresARealPositiveAmount() {
        assertFalse(CaptureReviewPolicy.canConfirm(0.0))
        assertFalse(CaptureReviewPolicy.canConfirm(-1.0))
        assertFalse(CaptureReviewPolicy.canConfirm(Double.NaN))
        assertFalse(CaptureReviewPolicy.canConfirm(Double.POSITIVE_INFINITY))
        assertTrue(CaptureReviewPolicy.canConfirm(0.01))
    }
    @Test fun automaticConfirmationRequiresCategoryAndUsableSuggestion() {
        assertFalse(CaptureReviewPolicy.canAutoConfirm(20.0, "", 0.9f))
        assertFalse(CaptureReviewPolicy.canAutoConfirm(20.0, "food", null))
        assertFalse(CaptureReviewPolicy.canAutoConfirm(20.0, "food", Float.NaN))
        assertFalse(CaptureReviewPolicy.canAutoConfirm(20.0, "food", 0.49f))
        assertTrue(CaptureReviewPolicy.canAutoConfirm(20.0, "food", 0.5f))
    }
    @Test fun equalAmountsAcrossSourcesAreFlaggedNotDiscarded() {
        val a = TransactionRecord(id = 1, amount = 20.0, source = TransactionRecord.SOURCE_CAPTURE_SMS, timestamp = 1000)
        val b = TransactionRecord(id = 2, amount = 20.0, source = TransactionRecord.SOURCE_CAPTURE_NOTIFICATION, timestamp = 2000)
        val c = TransactionRecord(id = 3, amount = 20.0, source = TransactionRecord.SOURCE_CAPTURE_SMS, timestamp = 900_000)
        val d = TransactionRecord(id = 4, type = TransactionRecord.TYPE_INCOME, amount = 20.0, source = TransactionRecord.SOURCE_CAPTURE_NOTIFICATION, timestamp = 2000)
        assertEquals(setOf(1L, 2L), CaptureReviewPolicy.possiblyDuplicateIds(listOf(a, b, c, d)))
    }
}
