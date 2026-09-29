package com.mudasir.smartledger.ml

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mudasir.smartledger.data.TransactionRecord
import java.util.Calendar
import kotlin.math.ln
import kotlin.math.max

object PersonalTagger {

    private const val FILE = "tagger_model.json"
    private const val TAG = "PersonalTagger"
    private const val MIN_SAMPLES = 5

    private data class Model(
        var total: Int = 0,
        var prior: MutableMap<String, Int> = mutableMapOf(),
        var feat: MutableMap<String, MutableMap<String, MutableMap<String, Int>>> = mutableMapOf()
    )

    private val FEATURES = listOf("hour", "amount", "weekday", "channel")

    private fun file(context: Context) = java.io.File(context.applicationContext.filesDir, FILE)

    private fun load(context: Context): Model {
        return try {
            val raw = file(context).readText()
            val type = object : TypeToken<Model>() {}.type
            (Gson().fromJson(raw, type) as? Model) ?: Model()
        } catch (e: Exception) {
            Model()
        }
    }

    private fun save(context: Context, m: Model) {
        try {
            file(context).apply { parentFile?.mkdirs() }.writeText(Gson().toJson(m))
        } catch (e: Exception) {
            Log.w(TAG, "save model failed", e)
        }
    }

    fun recommend(
        context: Context,
        type: String,
        timestamp: Long,
        amount: Double,
        channel: String,
        merchant: String?
    ): String {
        val m = load(context)
        val feats = featuresOf(type, timestamp, amount, channel)
        if (m.total >= MIN_SAMPLES && m.prior.isNotEmpty()) {
            val nb = bayesArgmax(m, feats)
            if (nb != null) return nb
        }
        return rulePredict(type, timestamp, amount, channel, merchant)
            ?: defaultCategory(type)
    }

    fun learn(
        context: Context,
        type: String,
        timestamp: Long,
        amount: Double,
        channel: String,
        category: String
    ) {
        if (category.isBlank()) return
        val m = load(context)
        val feats = featuresOf(type, timestamp, amount, channel)
        m.total++
        m.prior[category] = (m.prior[category] ?: 0) + 1
        for ((f, v) in feats) {
            m.feat.getOrPut(f) { mutableMapOf() }
                .getOrPut(v) { mutableMapOf() }
                .let { it[category] = (it[category] ?: 0) + 1 }
        }
        save(context, m)
    }

    fun correct(
        context: Context,
        type: String,
        timestamp: Long,
        amount: Double,
        channel: String,
        oldCategory: String?,
        newCategory: String
    ) {
        if (newCategory.isBlank()) return
        val m = load(context)
        val feats = featuresOf(type, timestamp, amount, channel)
        if (!oldCategory.isNullOrBlank() && oldCategory != newCategory) {
            m.total = max(0, m.total - 1)
            val p = m.prior[oldCategory]
            if (p != null && p > 0) m.prior[oldCategory] = p - 1
            for ((f, v) in feats) {
                val inner = m.feat[f]?.get(v)?.get(oldCategory)
                if (inner != null && inner > 0) m.feat[f]!![v]!![oldCategory] = inner - 1
            }
        }
        m.total++
        m.prior[newCategory] = (m.prior[newCategory] ?: 0) + 1
        for ((f, v) in feats) {
            m.feat.getOrPut(f) { mutableMapOf() }
                .getOrPut(v) { mutableMapOf() }
                .let { it[newCategory] = (it[newCategory] ?: 0) + 1 }
        }
        save(context, m)
    }

    // ---- 特征提取 ----
    private fun featuresOf(type: String, ts: Long, amount: Double, channel: String): Map<String, String> {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return mapOf(
            "hour" to hourBand(c.get(Calendar.HOUR_OF_DAY)),
            "amount" to amountBand(amount),
            "weekday" to weekdayBand(c.get(Calendar.DAY_OF_WEEK)),
            "channel" to (channel.trim().ifBlank { "未知" })
        )
    }

    private fun hourBand(h: Int): String = when (h) {
        in 6..10 -> "morning"
        in 11..13 -> "lunch"
        in 14..16 -> "afternoon"
        in 17..20 -> "dinner"
        in 21..23 -> "night"
        else -> "late"
    }

    private fun amountBand(a: Double): String = when {
        a < 20 -> "tiny"
        a < 50 -> "small"
        a < 150 -> "medium"
        a < 500 -> "large"
        else -> "big"
    }

    private fun weekdayBand(w: Int): String = if (w == 1 || w == 7) "weekend" else "workday"

    // ---- 朴素贝叶斯预测 ----
    private fun bayesArgmax(m: Model, feats: Map<String, String>): String? {
        var bestCat: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        val vocab = max(m.prior.size, 1)
        for (cat in m.prior.keys) {
            val priorP = (m.prior[cat] ?: 0).toDouble() / max(m.total, 1)
            var logp = ln(priorP.coerceAtLeast(1e-6))
            for ((f, v) in feats) {
                val fc = m.feat[f]?.get(v)
                val sumFc = fc?.values?.sum() ?: 0
                val c = fc?.get(cat) ?: 0
                val p = (c + 1.0) / (sumFc + vocab)
                logp += ln(p)
            }
            if (logp > bestScore) {
                bestScore = logp
                bestCat = cat
            }
        }
        return bestCat
    }

    // ---- 规则冷启动 ----
    private fun rulePredict(type: String, ts: Long, amount: Double, channel: String, merchant: String?): String? {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val band = amountBand(amount)
        val combined = ((merchant.orEmpty()) + " " + channel).lowercase()
        val ch = channel.lowercase()

        fun has(vararg kw: String) = kw.any { combined.contains(it) }
        fun chHas(vararg kw: String) = kw.any { ch.contains(it) }

        if (type != TransactionRecord.TYPE_EXPENSE) {
            return when {
                has("工资", "薪", "薪资") -> "基本工资"
                has("奖金", "提成", "绩效") -> "奖金提成"
                has("利息", "分红", "收益") -> "利息分红"
                has("基金", "股票", "理财", "余额宝") -> "基金股票"
                has("红包") -> "收发红包"
                has("退款", "退回", "退") -> "转账退款"
                else -> null
            }
        }

        return when {
            // --- 外卖 ---
            has("美团", "饿了吗", "饿了么", "外卖", "keeta", "kfc", "麦当劳", "汉堡", "肯德基") -> "外卖"

            // --- 交通 ---
            has("地铁") -> "地铁"
            has("公交", "巴士", "乘车") -> "公交"
            has("滴滴", "打车", "出租", "网约", "出行", "t3", "曹操", "首汽") -> "打车"
            has("加油", "中石化", "中石油", "壳牌", "加油站") -> "加油停车"
            has("停车") -> "加油停车"
            has("火车", "高铁", "机票", "航班", "航空", "12306", "携程", "去哪儿") -> "火车机票"

            // --- 通讯 ---
            has("话费", "移动", "联通", "电信", "运营商") -> "话费"
            has("流量", "数据包") -> "流量"

            // --- 网购（按渠道判定）---
            chHas("淘宝", "天猫") || has("淘宝", "天猫") -> {
                when {
                    has("服饰", "衣服", "鞋", "包", "服装", "裙") -> "服饰"
                    has("数码", "电子", "手机", "电脑", "耳机", "充电", "配件", "键盘") -> "数码"
                    has("美妆", "护肤", "化妆", "面膜", "口红") -> "美妆护肤"
                    band == "big" -> "数码"
                    band == "large" -> "服饰"
                    else -> "日用品"
                }
            }
            chHas("京东", "jd") || has("京东") -> {
                when {
                    has("数码", "电子", "手机", "电脑", "家电", "电器", "耳机") -> "数码"
                    has("服饰", "衣服", "鞋", "包") -> "服饰"
                    band == "big" || band == "large" -> "数码"
                    else -> "日用品"
                }
            }
            chHas("拼多多", "拼多多") || has("拼多多") -> "日用品"
            chHas("苏宁", "当当") || has("苏宁", "当当") -> "数码"

            // --- 娱乐 ---
            has("电影", "演出", "票务", "影院", "猫眼", "大麦") -> "电影演出"
            has("游戏", "steam", "psn", "nintendo", "switch", "xbox", "腾讯游戏", "网易游戏", "原神", "王者") -> "游戏充值"
            has("旅行", "旅游", "酒店", "民宿", "机票", "飞猪", "同程") -> "旅行出游"

            // --- 医疗 ---
            has("药", "药店", "医院", "挂号", "门诊", "诊所", "健康") -> "挂号门诊"
            has("药品", "处方", "胶囊", "片") -> "药品"

            // --- 居住 ---
            has("房租", "租金", "押金") -> "房租"
            has("电费", "水费", "燃气", "物业", "宽带", "网费") -> "水电燃气"

            // --- 教育 ---
            has("课程", "培训", "学费", "网课", "得到", "极客", "知识付费") -> "课程培训"
            has("书", "文具", "教材", "kindle") -> "书籍文具"

            // --- 日用/超市 ---
            has("超市", "便利店", "永辉", "沃尔玛", "家乐福", "711", "全家", "罗森", "盒马", "大润发") -> "超市日用"

            // --- 餐饮（仅在以上均不命中时才走时段+金额兜底）---
            has("食堂", "公司餐") -> "食堂"
            has("星巴克", "瑞幸", "咖啡", "奶茶", "喜茶", "蜜雪", "茶颜", "库迪", "manner") -> "零食饮料"
            has("下馆子", "聚餐", "餐厅", "饭店") -> "下馆子"

            // --- 时段+金额兜底（更保守，不再把所有午饭都归外卖）---
            h in 11..13 && band in setOf("tiny") && has("公司", "学校", "园区", "单位") -> "食堂"
            h in 11..13 && band in setOf("medium", "large") -> "下馆子"
            h in 17..20 && band in setOf("medium", "large") -> "下馆子"
            h in 6..9 && band == "tiny" -> "公交"

            else -> null
        }
    }

    private fun defaultCategory(type: String): String =
        if (type == TransactionRecord.TYPE_EXPENSE) "其他" else "其他收入"
}
