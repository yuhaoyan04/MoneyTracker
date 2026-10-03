package com.mudasir.smartledger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.Calendar

/**
 * 统一交易记录实体。
 *
 * 来源：自动抓取（通知/短信）或手动录入。自动抓取默认 PENDING，进收件箱确认后 CONFIRMED。
 * v9 起新增地理信息（latitude/longitude/locationName）与支付地点，便于后续地图与个性化打标。
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

    /** 分类 id（可空，聚合用）；categoryName 为展示与判别主键。 */
    val categoryId: Long? = null,
    val categoryName: String = "",

    /** 渠道：微信支付 / 支付宝 / 京东 / 银行卡 / 现金 … */
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

    /** v9: 地理信息。自动抓取/手动确认时尽量写入。 */
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationName: String? = null,

    /** v11: AI 打标时的置信度 [0,1]，用于收件箱展示「AI 信心」。 */
    val aiConfidence: Float? = null,

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

    // ---- 计算属性（不入库，仅供图表/打标器使用） ----
    private fun cal(): Calendar = Calendar.getInstance().apply { timeInMillis = timestamp }

    /** 当月第几日（1..31）。 */
    val day: Int get() = cal().get(Calendar.DAY_OF_MONTH)
    /** 0..23。 */
    val hour: Int get() = cal().get(Calendar.HOUR_OF_DAY)
    /** 1=周日 .. 7=周六。 */
    val weekday: Int get() = cal().get(Calendar.DAY_OF_WEEK)
    /** 月份 0..11。 */
    val month0: Int get() = cal().get(Calendar.MONTH)
    val year: Int get() = cal().get(Calendar.YEAR)
}
