package com.mudasir.smartledger.util

import android.content.Context
import android.graphics.Color

/**
 * 分类 → 颜色映射，复用于交易列表、收件箱、统计图表。
 * 大类有固定语义色，小类继承父级色，未匹配使用主品牌色。
 */
object CategoryStyle {

    private val rootColors = mapOf(
        "餐饮" to "#FF7043", "交通" to "#29B6F6", "网购" to "#AB47BC",
        "日用" to "#66BB6A", "娱乐" to "#FFCA28", "医疗" to "#EF5350",
        "居住" to "#78909C", "通讯" to "#26A69A", "教育" to "#5C6BC0",
        "其他" to "#90A4AE",
        "工资" to "#66BB6A", "理财" to "#26A69A", "红包" to "#EF5350",
        "退款" to "#29B6F6", "其他收入" to "#90A4AE"
    )

    private val childToRoot = mapOf(
        "食堂" to "餐饮", "外卖" to "餐饮", "下馆子" to "餐饮", "零食饮料" to "餐饮",
        "地铁" to "交通", "公交" to "交通", "打车" to "交通", "火车机票" to "交通", "加油停车" to "交通",
        "服饰" to "网购", "数码" to "网购", "日用品" to "网购", "美妆护肤" to "网购",
        "超市日用" to "日用",
        "电影演出" to "娱乐", "游戏充值" to "娱乐", "旅行出游" to "娱乐",
        "挂号门诊" to "医疗", "药品" to "医疗",
        "房租" to "居住", "水电燃气" to "居住", "物业宽带" to "居住",
        "话费" to "通讯", "流量" to "通讯",
        "课程培训" to "教育", "书籍文具" to "教育",
        "基本工资" to "工资", "奖金提成" to "工资",
        "利息分红" to "理财", "基金股票" to "理财",
        "收发红包" to "红包",
        "转账退款" to "退款"
    )

    fun color(context: Context, categoryName: String?, parentName: String? = null): Int {
        val hex = when {
            categoryName != null && rootColors.containsKey(categoryName) -> rootColors[categoryName]
            parentName != null && rootColors.containsKey(parentName) -> rootColors[parentName]
            categoryName != null && childToRoot.containsKey(categoryName) -> rootColors[childToRoot[categoryName]]
            else -> "#179A9D"
        }
        return Color.parseColor(hex)
    }

    fun initial(categoryName: String?): String {
        if (categoryName.isNullOrBlank()) return "·"
        return categoryName.firstOrNull()?.toString() ?: "·"
    }
}
