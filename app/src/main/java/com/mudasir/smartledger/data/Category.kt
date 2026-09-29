package com.mudasir.smartledger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户自定义分类。支持自由新增，不再只能从固定标签选择。
 * type 限定该分类用于支出或收入。
 *
 * v9: 支持多级（parentName + level）。level=1 为一级（如 餐饮/交通），
 * level=2 为二级（如 食堂/外卖，其 parentName=餐饮）。UI 按二级层级展示，
 * 避免用户把同一类支出打成不同名字导致统计错分。
 */
@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["name", "type"], unique = true),
        Index(value = ["parentName"])
    ]
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
    /** 父分类名称（一级分类为 null）。 */
    val parentName: String? = null,
    /** 层级：1=一级，2=二级… */
    val level: Int = 1,
    val createdAt: Long = System.currentTimeMillis()
)
