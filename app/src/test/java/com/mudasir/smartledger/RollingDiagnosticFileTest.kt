package com.mudasir.smartledger

import com.mudasir.smartledger.util.RollingDiagnosticFile
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RollingDiagnosticFileTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `rotation and export preserve complete newest records`() {
        val store = RollingDiagnosticFile(temporary.newFolder(), limit = 8)
        listOf("aaa", "bbb", "ccc", "ddd", "eee").forEach(store::append)
        val out = ByteArrayOutputStream()
        val bytes = store.copyTo(out)
        val text = out.toString("UTF-8")
        assertEquals("ccc\nddd\neee\n", text)
        assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), bytes)
        store.clear()
        assertFalse(store.current.exists())
        assertFalse(store.previous.exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `one event cannot insert a second log line`() {
        RollingDiagnosticFile(temporary.newFolder()).append("safe\nunsafe")
    }
}
