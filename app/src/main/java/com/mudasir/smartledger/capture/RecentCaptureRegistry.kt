package com.mudasir.smartledger.capture

/**
 * 跨通道抓取去重（同进程共享）：
 * 通知监听、无障碍服务可能先后捕获同一笔支付（如「支付成功结果页 + 微信支付凭证通知」）。
 * 90 秒窗口内同类型 + 同金额 → 视为同一笔，仅保留先到者。
 */
object RecentCaptureRegistry {

    private data class Entry(val time: Long, val type: String, val amount: Double)

    private val entries = mutableListOf<Entry>()

    /** 返回 true = 重复（近期已捕获过同一笔），调用方应放弃本次入库。 */
    @Synchronized
    fun isDuplicate(type: String, amount: Double): Boolean {
        val now = System.currentTimeMillis()
        entries.removeAll { it.time < now - 90_000 }
        val dup = entries.any { it.type == type && kotlin.math.abs(it.amount - amount) < 0.01 }
        return if (dup) {
            true
        } else {
            entries.add(Entry(now, type, amount))
            false
        }
    }
}
