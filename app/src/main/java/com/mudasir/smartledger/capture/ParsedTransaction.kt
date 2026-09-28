package com.mudasir.smartledger.capture

/**
 * 解析后的交易（来自通知或短信），尚未入库。
 * 由 [TransactionParser] 产出，再由监听服务/短信接收器写入数据库为 PENDING 记录。
 */
data class ParsedTransaction(
    val type: String,
    val amount: Double,
    val merchant: String? = null,
    val channelName: String,
    val paymentMethod: String? = null,
    val rawText: String,
    val source: String,
    val packageName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
