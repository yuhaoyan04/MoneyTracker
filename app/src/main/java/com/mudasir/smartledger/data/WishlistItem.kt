package com.mudasir.smartledger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 想好再买 · 愿望清单条目。
 *
 * 记录「我想买」的大额消费，24 小时后自动回访「还想买吗」：
 * - WAITING：冷静中，等待回访
 * - BOUGHT：还想买，已购入
 * - DROPPED：不要了（计入「已省下」金额，正向激励）
 */
@Entity(
    tableName = "wishlist_items",
    indices = [Index(value = ["createdAt"]), Index(value = ["status"])]
)
data class WishlistItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,

    val amount: Double,

    val createdAt: Long = System.currentTimeMillis(),

    /** WAITING / BOUGHT / DROPPED */
    val status: String = STATUS_WAITING,

    val resolvedAt: Long? = null
) {
    companion object {
        const val STATUS_WAITING = "WAITING"
        const val STATUS_BOUGHT = "BOUGHT"
        const val STATUS_DROPPED = "DROPPED"
    }
}
