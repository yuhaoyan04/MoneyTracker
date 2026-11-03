package com.mudasir.smartledger.util

/** Failure metadata only; never serialize page text or event/node content. */
object GuardFailureDetails {
    fun format(error: Throwable): String = buildString {
        val visited = mutableSetOf<Throwable>()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 5 && visited.add(current)) {
            val cause = current
            append(if (depth == 0) "异常：" else "\n原因：")
            append(cause.javaClass.name)
            cause.message?.take(350)?.let { append("\n说明：").append(it) }
            cause.stackTrace.take(8).forEach { frame ->
                append("\n  ").append(frame.className).append('.').append(frame.methodName)
                    .append(':').append(frame.lineNumber)
            }
            current = cause.cause
            depth++
        }
    }.take(6000)
}
