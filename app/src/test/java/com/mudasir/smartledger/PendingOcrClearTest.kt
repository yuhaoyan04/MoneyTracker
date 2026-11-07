package com.mudasir.smartledger

import com.mudasir.smartledger.capture.PendingOcrClear
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingOcrClearTest {
    @Test fun `blank result clears only on unchanged screen unless superseded`() {
        val gate = PendingOcrClear()
        val first = gate.schedule()
        assertTrue(gate.shouldClear(first, true))
        assertFalse(gate.shouldClear(first, false))
        gate.cancel()
        assertFalse(gate.shouldClear(first, true))
        val second = gate.schedule()
        assertTrue(gate.shouldClear(second, true))
        gate.schedule()
        assertFalse(gate.shouldClear(second, true))
    }
}
