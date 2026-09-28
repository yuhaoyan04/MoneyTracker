package com.mudasir.smartledger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 统一交易记录实体。
 *
 * 一条记录可以来自：
 *  - 自动抓取（支付 App 通知 / 银行短信）：source = CAPTURE_NOTIFICATION / CAPTURE_SMS
 *  - 手动录入：source = MANUAL
 *
 * 自动抓取的记录默认 status = PENDING，进入「待确认收件箱」由用户核对后再 CONFIRMED，
 * 既保证「不遗漏每一条信息」，又允许用户修正分类/渠道/金额。
 */
@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["status"]),
        Index(value = ["type"]),
        Index(value = ["categoryName"]),
        Index(value = ["channelName"])
    ]
)
data class TransactionRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** EXPENSE / INCOME */
    val type: String = TYPE_EXPENSE,

    val amount: Double = 0.0,

    /** 自定义分类名称（自由文本，便于用户自定义）。同时保留 categoryId 便于聚合。 */
    val categoryId: Long? = null,
    val categoryName: String = "",

    /** 渠道：微信支付 / 支付宝 / 京东 / 淘宝 / 银行卡 / 现金 … 自定义。 */
    val channelName: String = "",

    /** 支付方式：余额 / 储蓄卡 / 信用卡 / 花呗 … 可空。 */
    val paymentMethod: String? = null,

    /** 商户 / 标题。 */
    val merchant: String? = null,

    val note: String? = null,

    val timestamp: Long = System.currentTimeMillis(),

    /** CAPTURE_NOTIFICATION / CAPTURE_SMS / MANUAL */
    val source: String = SOURCE_MANUAL,

    /** 原始文本（通知 / 短信原文），用于溯源，确保不遗漏。 */
    val rawText: String? = null,

    /** 产生该记录的 App 包名（如 com.tencent.mm），仅自动抓取有值。 */
    val packageName: String? = null,

    /** PENDING / CONFIRMED / DISMISSED */
    val status: String = STATUS_PENDING,

    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,

    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_EXPENSE = "EXPENSE"
        const val TYPE_INCOME = "INCOME"

        const val SOURCE_MANUAL = "MANUAL"
        const val SOURCE_CAPTURE_NOTIFICATION = "CAPTURE_NOTIFICATION"
        const val SOURCE_CAPTURE_SMS = "CAPTURE_SMS"

        const val STATUS_PENDING = "PENDING"
        const val STATUS_CONFIRMED = "CONFIRMED"
        const val STATUS_DISMISSED = "DISMISSED"
    }
}
