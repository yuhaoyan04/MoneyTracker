package com.mudasir.smartledger.util

import java.io.File
import java.io.OutputStream

/** Two complete-line segments; callers serialize access on a single worker. */
class RollingDiagnosticFile(private val directory: File, private val limit: Long = 128 * 1024L) {
    val current = File(directory, "guard-diagnostic.jsonl")
    val previous = File(directory, "guard-diagnostic.previous.jsonl")

    fun append(line: String) {
        require('\n' !in line && '\r' !in line)
        check(directory.exists() || directory.mkdirs()) { "cannot create log directory" }
        if (current.exists() && current.length() >= limit) {
            if (previous.exists() && !previous.delete()) error("rotation failed")
            if (!current.renameTo(previous)) error("rotation failed")
        }
        current.appendText(line + "\n", Charsets.UTF_8)
    }

    fun copyTo(output: OutputStream): Long {
        val files = listOf(previous, current).filter { it.exists() && it.length() > 0 }
        check(files.isNotEmpty()) { "no diagnostic records" }
        return files.sumOf { file -> file.inputStream().use { it.copyTo(output) } }
    }

    fun clear() {
        listOf(previous, current).forEach { if (it.exists() && !it.delete()) error("cannot clear log") }
    }
}
