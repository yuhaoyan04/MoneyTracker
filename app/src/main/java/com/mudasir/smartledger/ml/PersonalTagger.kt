package com.mudasir.smartledger.ml

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mudasir.smartledger.data.TransactionRecord
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 个性化消费打标系统 —— 三层架构：
 *
 * 1) 商户记忆（merchant memory）：最强国号。用户给某商户打过一次标，
 *    下次该商户出现即直接复用。支持子串模糊匹配，命中率随使用增长。
 *
 * 2) 朴素贝叶斯 + 金额区间（Naive Bayes + amount range）：四特征边际
 *    计数（时段/金额段/工作日/渠道）+ 每类金额均值±标准差对数增益。
 *    样本越多越准，能捕捉「A 中午食堂、B 下午外卖」这类个体差异。
 *
 * 3) 规则引擎（rule engine）：冷启动兜底。覆盖 30+ 高频场景关键词，
 *    并按渠道/商户/时段/金额多维度判定。
 *
 * 预测优先级：商户记忆 → 贝叶斯 → 规则 → 默认「其他」。
 * 随用户使用，前两层逐渐主导，规则退居兜底。
 */
object PersonalTagger {

    private const val FILE = "tagger_model.json"
    private const val TAG = "PersonalTagger"
    private const val MIN_SAMPLES = 5
    private const val MERCHANT_MIN = 1

    data class CatStat(
        var count: Int = 0,
        var sumAmount: Double = 0.0,
        var sumSqAmount: Double = 0.0
    ) {
        val mean: Double get() = if (count > 0) sumAmount / count else 0.0
        val std: Double get() = if (count > 1) {
            val v = sumSqAmount / count - mean * mean
            sqrt(v.coerceAtLeast(0.0))
        } else 0.0
    }

    data class Model(
        var total: Int = 0,
        var prior: MutableMap<String, Int> = mutableMapOf(),
        var feat: MutableMap<String, MutableMap<String, MutableMap<String, Int>>> = mutableMapOf(),
        var merchantMap: MutableMap<String, MutableMap<String, Int>> = mutableMapOf(),
        var catStats: MutableMap<String, CatStat> = mutableMapOf()
    )

    private val FEATURES = listOf("hour", "amount", "weekday", "channel")

    private fun file(context: Context) = java.io.File(context.applicationContext.filesDir, FILE)

    private fun load(context: Context): Model {
        return try {
            val raw = file(context).readText()
            val type = object : TypeToken<Model>() {}.type
            val m = (Gson().fromJson(raw, type) as? Model) ?: Model()
            @Suppress("SENSELESS_COMPARISON")
            if (m.prior == null) m.prior = mutableMapOf()
            @Suppress("SENSELESS_COMPARISON")
            if (m.feat == null) m.feat = mutableMapOf()
            @Suppress("SENSELESS_COMPARISON")
            if (m.merchantMap == null) m.merchantMap = mutableMapOf()
            @Suppress("SENSELESS_COMPARISON")
            if (m.catStats == null) m.catStats = mutableMapOf()
            m
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

    // ---- 公开 API ----

    fun recommend(
        context: Context,
        type: String,
        timestamp: Long,
        amount: Double,
        channel: String,
        merchant: String?
    ): String {
        val m = load(context)
        val norm = normalizeMerchant(merchant)

        // 1. 商户记忆
        if (norm.isNotEmpty()) {
            bestMerchantCategory(m, norm)?.let { return it }
        }

        // 2. 贝叶斯 + 金额区间
        if (m.total >= MIN_SAMPLES && m.prior.isNotEmpty()) {
            val feats = featuresOf(type, timestamp, amount, channel)
            bayesArgmax(m, feats, amount)?.let { return it }
        }

        // 3. 规则
        rulePredict(type, timestamp, amount, channel, merchant)?.let { return it }

        // 4. 默认
        return defaultCategory(type)
    }

    fun learn(
        context: Context,
        type: String,
        timestamp: Long,
        amount: Double,
        channel: String,
        category: String,
        merchant: String? = null
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

        val norm = normalizeMerchant(merchant)
        if (norm.isNotEmpty()) {
            m.merchantMap.getOrPut(norm) { mutableMapOf() }
                .let { it[category] = (it[category] ?: 0) + 1 }
        }

        updateCatStats(m, category, amount, +1)

        save(context, m)
    }

    fun correct(
        context: Context,
        type: String,
        timestamp: Long,
        amount: Double,
        channel: String,
        oldCategory: String?,
        newCategory: String,
        merchant: String? = null
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
            val norm = normalizeMerchant(merchant)
            if (norm.isNotEmpty()) {
                m.merchantMap[norm]?.let { cats ->
                    val c = cats[oldCategory]
                    if (c != null && c > 0) cats[oldCategory] = c - 1
                }
            }
            updateCatStats(m, oldCategory, amount, -1)
        }

        m.total++
        m.prior[newCategory] = (m.prior[newCategory] ?: 0) + 1
        for ((f, v) in feats) {
            m.feat.getOrPut(f) { mutableMapOf() }
                .getOrPut(v) { mutableMapOf() }
                .let { it[newCategory] = (it[newCategory] ?: 0) + 1 }
        }
        val norm = normalizeMerchant(merchant)
        if (norm.isNotEmpty()) {
            m.merchantMap.getOrPut(norm) { mutableMapOf() }
                .let { it[newCategory] = (it[newCategory] ?: 0) + 1 }
        }
        updateCatStats(m, newCategory, amount, +1)

        save(context, m)
    }

    // ---- 商户记忆 ----

    private fun normalizeMerchant(s: String?): String {
        if (s.isNullOrBlank()) return ""
        return s.lowercase()
            .replace(Regex("[-_](订单|支付|收款|消费|付款|到账|扣款|转账)$"), "")
            .replace(Regex("^(微信支付|支付宝|京东|淘宝|天猫)-"), "")
            .trim()
    }

    private fun bestMerchantCategory(m: Model, merchant: String): String? {
        // 精确匹配
        m.merchantMap[merchant]?.let { cats ->
            val best = cats.maxByOrNull { it.value }
            if (best != null && best.value >= MERCHANT_MIN) return best.key
        }
        // 子串匹配（双向，要求 key 长度 >= 2 避免误匹配）
        for ((key, cats) in m.merchantMap) {
            if (key.length < 2) continue
            if (merchant.contains(key) || key.contains(merchant)) {
                val best = cats.maxByOrNull { it.value }
                if (best != null && best.value >= MERCHANT_MIN) return best.key
            }
        }
        return null
    }

    // ---- 特征 + 贝叶斯 ----

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

    private fun updateCatStats(m: Model, cat: String, amount: Double, delta: Int) {
        val s = m.catStats.getOrPut(cat) { CatStat() }
        if (delta > 0) {
            s.count++
            s.sumAmount += amount
            s.sumSqAmount += amount * amount
        } else if (s.count > 0) {
            s.count--
            s.sumAmount -= amount
            s.sumSqAmount -= amount * amount
        }
    }

    private fun bayesArgmax(m: Model, feats: Map<String, String>, amount: Double): String? {
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
            // 金额区间增益：交易金额落在此类均值附近 → 加分；远离 → 减分
            val stat = m.catStats[cat]
            if (stat != null && stat.count >= 3 && stat.std > 0) {
                val z = abs(amount - stat.mean) / stat.std
                logp += when {
                    z < 1.0 -> 0.5
                    z < 2.0 -> 0.2
                    else -> -0.3
                }
            }
            if (logp > bestScore) {
                bestScore = logp
                bestCat = cat
            }
        }
        return bestCat
    }

    // ---- 规则引擎 ----

    private fun rulePredict(type: String, ts: Long, amount: Double, channel: String, merchant: String?): String? {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val band = amountBand(amount)
        val combined = ((merchant.orEmpty()) + " " + channel).lowercase()
        val ch = channel.lowercase()
        val mRaw = (merchant.orEmpty()).lowercase()

        fun has(vararg kw: String) = kw.any { combined.contains(it) }
        fun chHas(vararg kw: String) = kw.any { ch.contains(it) }
        fun mHas(vararg kw: String) = kw.any { mRaw.contains(it) }

        if (type != TransactionRecord.TYPE_INCOME) {
            return when {
                has("美团", "饿了吗", "饿了么", "外卖", "keeta") -> "外卖"

                has("地铁") -> "地铁"
                has("公交", "巴士", "乘车码", "乘车") -> "公交"
                has("滴滴", "打车", "出租", "网约", "t3", "曹操", "首汽", "哈啰", "出行") -> "打车"
                has("加油", "中石化", "中石油", "壳牌", "加油站") -> "加油停车"
                has("停车费", "停车场", "停车") -> "加油停车"
                has("高铁", "火车", "12306", "机票", "航班", "航空", "携程", "去哪儿", "飞猪", "同程") -> "火车机票"

                has("话费", "移动", "联通", "电信", "运营商") -> "话费"
                has("流量包", "数据包") -> "流量"

                chHas("淘宝", "天猫") || mHas("淘宝", "天猫") -> when {
                    has("服饰", "衣服", "鞋", "包", "服装", "裙", "外套", "裤") -> "服饰"
                    has("数码", "电子", "手机", "电脑", "耳机", "充电", "配件", "键盘", "鼠标") -> "数码"
                    has("美妆", "护肤", "化妆", "面膜", "口红", "粉底") -> "美妆护肤"
                    has("食品", "零食", "水果", "生鲜") -> "超市日用"
                    band == "big" -> "数码"
                    band == "large" -> "服饰"
                    else -> "日用品"
                }
                chHas("京东", "jd") || mHas("京东") -> when {
                    has("数码", "电子", "手机", "电脑", "家电", "电器", "耳机", "电视") -> "数码"
                    has("服饰", "衣服", "鞋") -> "服饰"
                    has("食品", "生鲜", "水果") -> "超市日用"
                    else -> "数码"
                }
                chHas("拼多多") || mHas("拼多多") -> "日用品"
                chHas("苏宁", "当当") || mHas("苏宁", "当当") -> "数码"

                has("电影", "演出", "票务", "影院", "猫眼", "大麦", "淘票票") -> "电影演出"
                has("游戏", "steam", "psn", "nintendo", "switch", "xbox", "原神", "王者", "腾讯视频", "爱奇艺", "b站", "bilibili", "优酷", "网易云") -> "游戏充值"
                has("旅行", "旅游", "酒店", "民宿", "飞猪", "途家", "airbnb") -> "旅行出游"
                has("健身", "健身房", "瑜伽", "游泳", "运动") -> "旅行出游"

                has("药", "药店", "医院", "挂号", "门诊", "诊所", "体检") -> "挂号门诊"
                has("处方", "胶囊", "感冒药", "退烧") -> "药品"

                has("房租", "租金", "押金") -> "房租"
                has("电费", "水费", "燃气", "物业", "宽带", "网费", "暖气", "供暖") -> "水电燃气"

                has("课程", "培训", "学费", "网课", "得到", "极客", "知识付费") -> "课程培训"
                has("书", "文具", "教材", "kindle") -> "书籍文具"

                has("超市", "便利店", "永辉", "沃尔玛", "家乐福", "711", "全家", "罗森", "盒马", "大润发", "物美", "华润万家") -> "超市日用"

                has("食堂", "公司餐", "员工餐") -> "食堂"
                has("星巴克", "瑞幸", "咖啡", "manner", "costa") -> "零食饮料"
                has("奶茶", "喜茶", "蜜雪", "茶颜", "库迪", "coco", "一点点", "茶百道") -> "零食饮料"
                has("下馆子", "聚餐", "餐厅", "饭店", "火锅", "烧烤", "日料", "韩餐", "西餐") -> "下馆子"
                has("零食", "面包", "蛋糕", "甜品") -> "零食饮料"

                has("红包") -> "收发红包"
                has("转账", "转给") -> "其他"

                h in 11..13 && band == "tiny" && has("公司", "学校", "园区", "单位", "大厦") -> "食堂"
                h in 11..13 && band in setOf("medium", "large") -> "下馆子"
                h in 17..20 && band in setOf("medium", "large") -> "下馆子"
                h in 6..9 && band == "tiny" -> "公交"

                else -> null
            }
        }

        return when {
            has("工资", "薪", "薪资") -> "基本工资"
            has("奖金", "提成", "绩效") -> "奖金提成"
            has("利息", "分红", "收益", "理财收益") -> "利息分红"
            has("基金", "股票", "理财", "余额宝", "定期") -> "基金股票"
            has("红包") -> "收发红包"
            has("退款", "退回") -> "转账退款"
            else -> null
        }
    }

    private fun defaultCategory(type: String): String =
        if (type == TransactionRecord.TYPE_EXPENSE) "其他" else "其他收入"
}
