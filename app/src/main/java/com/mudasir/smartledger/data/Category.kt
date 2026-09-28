package com.mudasir.smartledger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户自定义分类。支持自由新增，不再只能从固定标签选择。
 * type 限定该分类用于支出或收入。
 */
@Entity(
    tableName = "categories",
    indices = [Index(value = ["name", "type"], unique = true)]
)
data class Category(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    /** EXPENSE / INCOME */
    val type: String = TransactionRecord.TYPE_EXPENSE,
    /** 颜色 hex（用于图表与标签），如 "#FF7043"。 */
    val color: String = "#179A9D",
    /** 图标资源名（可选）。 */
    val iconName: String? = null,
    val sortOrder: Int = 0,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
