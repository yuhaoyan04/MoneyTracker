package com.mudasir.smartledger.util

import com.google.gson.JsonParser
import com.mudasir.smartledger.data.LedgerBackupPayload
import com.mudasir.smartledger.data.LedgerJournalEntry
import com.mudasir.smartledger.data.TransactionBackupDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AutoBackupManagerTest {

    @Test
    fun envelopeRoundTripPreservesStablePayload() {
        val transaction = TransactionBackupDto(
            sourceId = 42,
            amount = 12.34,
            categoryName = "外卖",
            channelName = "微信支付",
            timestamp = 1_700_000_000_000,
            rawText = "支付成功 12.34元",
            createdAt = 1_700_000_000_100
        )
        val payload = LedgerBackupPayload(transactions = listOf(transaction))
        val journal = listOf(LedgerJournalEntry(1, 1_700_000_000_200, "UPSERT", "key", transaction))

        val json = AutoBackupManager.encodeEnvelope(payload, journal, 1_700_000_000_300, "test")
        val decoded = AutoBackupManager.decodeEnvelope(json)

        assertEquals(payload, decoded.payload)
        assertEquals(journal, decoded.journal)
        assertEquals(1, decoded.schemaVersion)
    }

    @Test
    fun corruptedPayloadIsRejected() {
        val json = AutoBackupManager.encodeEnvelope(
            LedgerBackupPayload(transactions = listOf(TransactionBackupDto(amount = 1.0))),
            emptyList(),
            1,
            "test"
        )
        val root = JsonParser.parseString(json).asJsonObject
        root.getAsJsonObject("payload").getAsJsonArray("transactions")
            .first().asJsonObject.addProperty("amount", 999.0)

        assertThrows(IllegalArgumentException::class.java) {
            AutoBackupManager.decodeEnvelope(root.toString())
        }
    }

    @Test
    fun legacySnapshotIsMigratedOnRead() {
        val legacy = """
            {
              "exportedAt": 1700000000000,
              "transactions": [{"id":7,"type":"EXPENSE","amount":8.5,"categoryName":"早餐","channelName":"支付宝","timestamp":1700000000000,"source":"MANUAL","status":"CONFIRMED","isDeleted":false,"createdAt":1700000000001}],
              "categories": [],
              "channels": []
            }
        """.trimIndent()

        val decoded = AutoBackupManager.decodeEnvelope(legacy)

        assertEquals(1, decoded.payload.transactions.size)
        assertEquals(7, decoded.payload.transactions.first().sourceId)
        assertEquals("legacy-v2.23", decoded.appVersion)
    }
}
