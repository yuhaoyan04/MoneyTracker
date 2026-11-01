package com.mudasir.smartledger.capture

/**
 * 跨通道抓取去重（同进程共享）：
 * 通知监听、无障碍服务、银行短信可能先后捕获同一笔支付
 * （如「微信支付凭证通知 + 银行扣款短信」是同一笔银行卡支付的两个通道表现）。
 *
 * 窗口语义：
 * - 通知 ↔ 无障碍（同一 App 内近乎同时）：默认 90 秒
 * - 短信 ↔ 通知/无障碍（银行短信常延迟 1-3 分钟）：调用方传 5 分钟
 *
 * 权衡说明：真实「连续两笔同额支付」在窗口内会被合并为一条（可在收件箱补记），
 * 但「同一笔支付被两个通道重复记录」是用户实测最高频的抱怨，宁合并勿重复。
 */
object RecentCaptureRegistry {

    private data class Entry(val time: Long, val type: String, val amount: Double)

    private val entries = mutableListOf<Entry>()

    /** 返回 true = 重复（近期已捕获过同一笔），调用方应放弃本次入库。未重复则自动登记本次。 */
    @Synchronized
    @JvmOverloads
    fun isDuplicate(type: String, amount: Double, windowMs: Long = 90_000L): Boolean {
        val now = System.currentTimeMillis()
        entries.removeAll { it.time < now - windowMs }
        val dup = entries.any {
            it.type == type && kotlin.math.abs(it.amount - amount) < 0.01
        }
        return if (dup) {
            true
        } else {
            entries.add(Entry(now, type, amount))
            false
        }
    }
}
