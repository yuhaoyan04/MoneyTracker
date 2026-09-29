package com.mudasir.smartledger.ml

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mudasir.smartledger.data.TransactionRecord
import java.util.Calendar
import kotlin.math.ln
import kotlin.math.max

/**
 * 个性化消费打标器。
 *
 * 设计思路（也是本产品的差异化卖点之一）：
 *
 * 1) 冷启动：规则引擎。用「时段 × 金额段 × 渠道/商户关键词」给出首次打标，
 *    覆盖食堂/外卖/外出用餐/通勤/网购等高频场景，让新用户一上来就有合理猜测。
 *
 * 2) 在线学习：朴素贝叶斯（多特征边际计数 + 拉普拉斯平滑）。每条「确认」或
 *    「人工修正」的记录都作为一条训练样本，按 [时段/金额段/工作日or周末/渠道] 四个
 *    独立特征更新计数。预测时对各候选类别做对数概率累加取 argmax。
 *    —— 相比「逐条精确签名」的查表法，边际计数可在未见过的组合上仍给出预测，
 *    且样本越多越准；修正记录会强化正确标签、弱化错误标签。
 *
 * 3) 模型文件 tagger_model.json 存于应用私有目录，随用户使用不断增长，纯本地、不上传。
 *
 * 预测优先级：训练样本 >= 阈值 → 贝叶斯 argmax；否则 → 规则；否则 → 兜底默认分类。
 */
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

    /** 预测一条交易应归入的分类名。 */
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

    /** 用一条已确认/已修正记录训练模型。 */
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

    /**
     * 人工修正：弱化旧标签、强化新标签。oldCategory 为打标器/规则当初给出的（被否决的）分类。
     * 若 oldCategory 为空或与 new 相同，则退化为普通 learn。
     */
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
        if (type != TransactionRecord.TYPE_EXPENSE) return null
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val band = amountBand(amount)
        val m = (merchant.orEmpty() + " " + channel).lowercase()

        fun has(vararg kw: String) = kw.any { m.contains(it) }

        return when {
            has("美团", "饿了吗", "饿了么", "外卖") -> "外卖"
            has("地铁") -> "地铁"
            has("公交", "巴士") -> "公交"
            has("滴滴", "打车", "出租", "网约") -> "打车"
            has("加油", "中石化", "中石油", "壳牌") -> "加油停车"
            has("停车") -> "加油停车"
            has("话费", "流量", "移动", "联通", "电信") -> "话费流量"
            has("淘宝", "京东", "拼多多", "天猫", "苏宁") ->
                when (amountBand(amount)) { "big" -> "数码"; "large" -> "服饰"; "medium" -> "服饰"; else -> "日用品" }
            has("电影", "演出", "票") -> "电影演出"
            has("游戏", "充值") -> "游戏充值"
            has("药", "药店", "医院", "挂号") -> "药品"
            has("电费", "水费", "燃气", "物业") -> "水电燃气"
            h in 11..13 && band in setOf("tiny", "small") && has("食堂", "公司", "学校", "园区") -> "食堂"
            h in 11..13 && band in setOf("small", "medium") -> "外卖"
            h in 11..13 && band in setOf("medium", "large") -> "外出用餐"
            h in 17..20 && band in setOf("small") -> "外卖"
            h in 17..20 && band in setOf("medium", "large") -> "外出用餐"
            h in 6..9 && band in setOf("tiny", "small") -> "公交"
            else -> null
        }
    }

    private fun defaultCategory(type: String): String =
        if (type == TransactionRecord.TYPE_EXPENSE) "餐饮" else "其他收入"
}
