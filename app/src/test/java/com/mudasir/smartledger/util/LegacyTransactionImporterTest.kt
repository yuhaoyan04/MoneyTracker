package com.mudasir.smartledger.util

import com.mudasir.smartledger.data.Expense
import com.mudasir.smartledger.data.TransactionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyTransactionImporterTest {
    @Test fun mapsLegacyExpenseIntoVisibleConfirmedTransaction() {
        val old = Expense(
            id = 7,
            title = "早餐",
            description = "豆浆油条",
            amount = 12.5,
            date = 1_700_000_000_000,
            isDeleted = false
        )
        val record = LegacyTransactionImporter.toTransaction(old)
        assertEquals(TransactionRecord.TYPE_EXPENSE, record.type)
        assertEquals(TransactionRecord.STATUS_CONFIRMED, record.status)
        assertEquals(12.5, record.amount, 0.0)
        assertEquals("早餐", record.merchant)
        assertEquals(old.date, record.timestamp)
        assertTrue(record.rawText!!.startsWith("legacy_expense:7:"))
    }
}
