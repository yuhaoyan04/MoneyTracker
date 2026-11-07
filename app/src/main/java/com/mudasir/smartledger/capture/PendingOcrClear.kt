package com.mudasir.smartledger.capture

/** A later OCR result supersedes a scheduled no-candidate clear. */
class PendingOcrClear {
    private var revision = 0L
    fun schedule(): Long = ++revision
    fun cancel() { revision++ }
    fun shouldClear(ticket: Long, sameScreenGeneration: Boolean): Boolean =
        sameScreenGeneration && ticket == revision
}
