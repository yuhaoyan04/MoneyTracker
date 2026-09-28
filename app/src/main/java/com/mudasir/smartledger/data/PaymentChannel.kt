package com.mudasir.smartledger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户自定义渠道：微信支付、支付宝、京东、淘宝、银行卡、现金等。
 * 用户可自由新增，不受预设限制。
 */
@Entity(
    tableName = "payment_channels",
    indices = [Index(value = ["name"], unique = true)]
)
data class PaymentChannel(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val iconName: String? = null,
    val isDefault: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
