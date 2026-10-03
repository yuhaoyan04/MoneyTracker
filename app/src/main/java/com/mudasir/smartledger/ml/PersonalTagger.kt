package com.mudasir.smartledger.ml

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mudasir.smartledger.data.TransactionRecord
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 个性化消费打标系统 —— 五层自进化架构（类短视频推荐引擎）：
 *
 * 1) 商户记忆（merchant memory）+ 加速置信度：
 *    Laplace 平滑 + 加速学习：3 次确认 0 纠正 → 0.92，5 次 → 0.97。
 *    2+ 次纠正 → 置信度封顶 0.49（避免重复错误）。
 *    高置信度（>=0.7）直接命中。
 *
 * 2) 商户-时段上下文（merchant × hour context）：
 *    同一商户在不同时段可能对应不同分类
 *    （美团@12→外卖, 美团@14→日用品）。
 *
 * 3) 朴素贝叶斯 + 金额区间（Naive Bayes + amount range）：
 *    四特征边际计数 + 金额均值±标准差对数增益。
 *
 * 4) 规则引擎（rule engine）：关键词 + 金额/时段/位置推断。
 *    4 层优先级：地理位置 > 强商户信号 > 网购平台细分 > 渠道+金额+时段。
 *
 * 5) 冷启动决策表（cold start table）：
 *    day-of-month × hour × weekday × channel × amount → category。
 *    月初房租、月末水电、心理定价(9.9/99/299)、收入时序(工资/红包/退款)。
 *    无关键词、无商户信息时仍能给出合理推断。
 *
 * 自进化机制（越来越准）：
 * - 加速学习：3 次确认即达高置信度，减少用户手动确认次数
 * - 分类频率先验：偏好用户常用分类（类短视频个性化默认推荐）
 * - 商户-时段上下文：同商户不同时段不同分类
 * - 纠正惩罚：频繁被纠正的商户置信度降低，让其他层接管
 * - 自适应权重：随数据量增长，merchant/Bayes 权重提升，rules/cold 退居兜底
 * - 时间衰减：90 天半衰期，旧样本权重降低，适应用户习惯变化
 * - 静默确认：用户未修正的 PENDING 记录 7 天后自动 learn()
 */
object PersonalTagger {

    private const val FILE = "tagger_model.json"
    private const val TAG = "PersonalTagger"
    private const val MIN_SAMPLES = 5
    private const val MERCHANT_MIN = 1
    private const val HIGH_CONFIDENCE = 0.7f
    private const val DECAY_HALF_LIFE_DAYS = 90.0
    private const val MERCHANT_FAST_HITS = 3
    private const val MERCHANT_EXPERT_HITS = 5
    private const val MERCHANT_CORRECT_PENALTY = 2

    private val lock = Any()

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
        var merchantHourMap: MutableMap<String, MutableMap<String, MutableMap<String, Int>>> = mutableMapOf(),
        var merchantCorrect: MutableMap<String, Int> = mutableMapOf(),
        var catStats: MutableMap<String, CatStat> = mutableMapOf(),
        var lastUpdate: Long = 0
    )

    private val FEATURES = listOf("hour", "amount", "weekday", "channel")

    private fun file(context: Context) = java.io.File(context.applicationContext.filesDir, FILE)

    private fun loadImpl(context: Context): Model {
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
            if (m.merchantHourMap == null) m.merchantHourMap = mutableMapOf()
            @Suppress("SENSELESS_COMPARISON")
            if (m.merchantCorrect == null) m.merchantCorrect = mutableMapOf()
            @Suppress("SENSELESS_COMPARISON")
            if (m.catStats == null) m.catStats = mutableMapOf()
            m
        } catch (e: Exception) {
            Model()
        }
    }

    private fun saveImpl(context: Context, m: Model) {
        val now = System.currentTimeMillis()
        if (m.lastUpdate > 0) {
            val elapsedDays = (now - m.lastUpdate) / 86400000.0
            if (elapsedDays > 7) {
                val factor = exp(-elapsedDays / DECAY_HALF_LIFE_DAYS)
                m.total = (m.total * factor).toInt().coerceAtLeast(1)
                m.prior.keys.toList().forEach { m.prior[it] = ((m.prior[it] ?: 0) * factor).toInt().coerceAtLeast(0) }
                for ((_, v1) in m.feat) {
                    for ((_, v2) in v1) {
                        v2.keys.toList().forEach { v2[it] = ((v2[it] ?: 0) * factor).toInt().coerceAtLeast(0) }
                    }
                }
                for ((_, cats) in m.merchantMap) {
                    cats.keys.toList().forEach { cats[it] = ((cats[it] ?: 0) * factor).toInt().coerceAtLeast(0) }
                }
                for ((_, hourMap) in m.merchantHourMap) {
                    for ((_, cats) in hourMap) {
                        cats.keys.toList().forEach { cats[it] = ((cats[it] ?: 0) * factor).toInt().coerceAtLeast(0) }
                    }
                }
                for ((_, stat) in m.catStats) {
                    stat.count = (stat.count * factor).toInt().coerceAtLeast(0)
                    stat.sumAmount *= factor
                    stat.sumSqAmount *= factor
                }
            }
        }
        m.lastUpdate = now
        try {
            file(context).apply { parentFile?.mkdirs() }.writeText(Gson().toJson(m))
        } catch (e: Exception) {
            Log.w(TAG, "save model failed", e)
        }
    }

    // ===== 公开 API =====

    fun recommend(
        context: Context,
        type: String,
        timestamp: Long,
        amount: Double,
        channel: String,
        merchant: String?,
        rawText: String? = null,
        locationName: String? = null
    ): String {
        val m = synchronized(lock) { loadImpl(context) }
        val norm = normalizeMerchant(merchant)

        // Layer 1: Merchant memory + confidence
        val merchantHit: Pair<String, Float>? = if (norm.isNotEmpty()) {
            bestMerchantWithConfidence(m, norm)
        } else null

        // High confidence → direct return
        if (merchantHit != null && merchantHit.second >= HIGH_CONFIDENCE) {
            return merchantHit.first
        }

        // Merchant + hour context (e.g. 美团@12→外卖, 美团@14→日用品)
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val hb = hourBand(cal.get(Calendar.HOUR_OF_DAY))
        val merchantHourHit: String? = if (norm.isNotEmpty()) {
            m.merchantHourMap[norm]?.get(hb)?.maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.key
        } else null

        // Layer 2: Bayes
        val bayesHit: String? = if (m.total >= MIN_SAMPLES && m.prior.isNotEmpty()) {
            val feats = featuresOf(type, timestamp, amount, channel)
            bayesArgmax(m, feats, amount)
        } else null

        // Layer 3: Rules (keyword + amount/time + location)
        val ruleHit: String? = rulePredict(type, timestamp, amount, channel, merchant, rawText, locationName)

        // Layer 4: Cold start decision table
        val coldHit: Pair<String, Float>? = coldStartPredict(type, timestamp, amount, channel)

        // === Adaptive weighted voting ===
        val merchantSamples = m.merchantMap[norm]?.values?.sum() ?: 0
        val wMerchant = if (merchantHit != null) min(0.6, 0.25 + merchantSamples * 0.02) else 0.0
        val wBayes = min(0.35, m.total.toDouble() / 100.0 * 0.35)
        val wRules = if (ruleHit != null) 0.45 else 0.0
        val wCold = (1.0 - wMerchant - wBayes - wRules).coerceIn(0.0, 0.5)

        val scores = mutableMapOf<String, Double>()
        merchantHit?.let { (cat, conf) -> scores[cat] = (scores[cat] ?: 0.0) + conf * wMerchant }
        bayesHit?.let { scores[it] = (scores[it] ?: 0.0) + 0.5 * wBayes }
        ruleHit?.let { scores[it] = (scores[it] ?: 0.0) + 0.6 * wRules }
        coldHit?.let { (cat, conf) -> scores[cat] = (scores[cat] ?: 0.0) + conf * wCold }

        // Merchant-hour context signal (weak but personalized)
        merchantHourHit?.let { scores[it] = (scores[it] ?: 0.0) + 0.15 }

        // === Category frequency prior — lean toward user's most consumed categories ===
        // Like short-video recommendation: default to user's habitual content
        if (m.total >= MIN_SAMPLES * 2 && m.prior.isNotEmpty()) {
            val priorTotal = m.prior.values.sum()
            val priorWeight = when {
                merchantHit == null && ruleHit == null && coldHit == null -> 0.20
                merchantHit == null && ruleHit == null -> 0.10
                else -> 0.03
            }
            m.prior.entries
                .sortedByDescending { it.value }
                .take(3)
                .forEachIndexed { i, (cat, cnt) ->
                    val freq = cnt.toDouble() / priorTotal
                    val boost = freq * priorWeight * (1.0 - i * 0.3)
                    scores[cat] = (scores[cat] ?: 0.0) + boost
                }
        }

        val best = scores.maxByOrNull { it.value }
        return best?.takeIf { it.value > 0.01 }?.key ?: defaultCategory(type)
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
        synchronized(lock) {
            val m = loadImpl(context)
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
                val hb = hourBand(Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.HOUR_OF_DAY))
                m.merchantHourMap.getOrPut(norm) { mutableMapOf() }
                    .getOrPut(hb) { mutableMapOf() }
                    .let { it[category] = (it[category] ?: 0) + 1 }
            }

            updateCatStats(m, category, amount, +1)
            saveImpl(context, m)
        }
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
        synchronized(lock) {
            val m = loadImpl(context)
            val feats = featuresOf(type, timestamp, amount, channel)
            val norm = normalizeMerchant(merchant)

            if (!oldCategory.isNullOrBlank() && oldCategory != newCategory) {
                m.total = max(0, m.total - 1)
                val p = m.prior[oldCategory]
                if (p != null && p > 0) m.prior[oldCategory] = p - 1
                for ((f, v) in feats) {
                    val inner = m.feat[f]?.get(v)?.get(oldCategory)
                    if (inner != null && inner > 0) m.feat[f]!![v]!![oldCategory] = inner - 1
                }
                if (norm.isNotEmpty()) {
                    m.merchantMap[norm]?.let { cats ->
                        val c = cats[oldCategory]
                        if (c != null && c > 0) cats[oldCategory] = c - 1
                    }
                    val hb = hourBand(Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.HOUR_OF_DAY))
                    m.merchantHourMap[norm]?.get(hb)?.let { cats ->
                        val c = cats[oldCategory]
                        if (c != null && c > 0) cats[oldCategory] = c - 1
                    }
                    m.merchantCorrect[norm] = (m.merchantCorrect[norm] ?: 0) + 1
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
            if (norm.isNotEmpty()) {
                m.merchantMap.getOrPut(norm) { mutableMapOf() }
                    .let { it[newCategory] = (it[newCategory] ?: 0) + 1 }
                val hb = hourBand(Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.HOUR_OF_DAY))
                m.merchantHourMap.getOrPut(norm) { mutableMapOf() }
                    .getOrPut(hb) { mutableMapOf() }
                    .let { it[newCategory] = (it[newCategory] ?: 0) + 1 }
            }
            updateCatStats(m, newCategory, amount, +1)

            saveImpl(context, m)
        }
    }

    // ===== 商户记忆 + 置信度 =====

    private fun normalizeMerchant(s: String?): String {
        if (s.isNullOrBlank()) return ""
        var r = s.lowercase()
            .replace(Regex("[-_](订单|支付|收款|消费|付款|到账|扣款|转账)$"), "")
            .replace(Regex("^(微信支付|支付宝|京东|淘宝|天猫)-"), "")
            .trim()
        r = resolveAlias(r)
        return r
    }

    private fun resolveAlias(m: String): String {
        return when {
            m.contains("美团") || m.contains("meituan") -> "美团"
            m.contains("饿") && m.contains("么") || m.contains("eleme") -> "饿了么"
            m.contains("滴滴") || m.contains("didi") -> "滴滴"
            m.contains("星巴克") || m.contains("starbucks") -> "星巴克"
            m.contains("瑞幸") || m.contains("luckin") -> "瑞幸"
            m.contains("肯德基") || m.contains("kfc") -> "肯德基"
            m.contains("麦当劳") || m.contains("mcdonald") -> "麦当劳"
            m.contains("淘宝") || m.contains("taobao") -> "淘宝"
            m.contains("天猫") || m.contains("tmall") -> "天猫"
            m.contains("京东") || m.contains("jd.com") || m.contains("jdcom") -> "京东"
            m.contains("拼多多") || m.contains("pinduoduo") -> "拼多多"
            m.contains("抖音") || m.contains("douyin") || m.contains("tiktok") -> "抖音"
            m.contains("快手") || m.contains("kuaishou") -> "快手"
            m.contains("喜茶") || m.contains("heytea") -> "喜茶"
            m.contains("蜜雪") || m.contains("mixue") -> "蜜雪冰城"
            m.contains("高德") || m.contains("amap") -> "高德地图"
            m.contains("百度地图") || m.contains("baidumap") -> "百度地图"
            m.contains("顺丰") || m.contains("sfexpress") -> "顺丰"
            else -> m
        }
    }

    private fun bestMerchantWithConfidence(m: Model, merchant: String): Pair<String, Float>? {
        var bestCat: String? = null
        var bestCount = 0
        var totalSamples = 0

        m.merchantMap[merchant]?.let { cats ->
            for ((cat, cnt) in cats) {
                totalSamples += cnt
                if (cnt > bestCount) { bestCount = cnt; bestCat = cat }
            }
        }

        if (bestCat == null) {
            for ((key, cats) in m.merchantMap) {
                if (key.length < 2) continue
                if (merchant.contains(key) || key.contains(merchant)) {
                    for ((cat, cnt) in cats) {
                        totalSamples += cnt
                        if (cnt > bestCount) { bestCount = cnt; bestCat = cat }
                    }
                    if (bestCat != null) break
                }
            }
        }

        if (bestCat == null || bestCount < MERCHANT_MIN) return null

        val corrections = m.merchantCorrect[merchant] ?: 0
        val confidence = when {
            bestCount >= MERCHANT_EXPERT_HITS && corrections == 0 -> 0.97f
            bestCount >= MERCHANT_FAST_HITS && corrections == 0 -> 0.92f
            corrections >= MERCHANT_CORRECT_PENALTY ->
                ((bestCount + 1).toFloat() / (totalSamples + corrections + 2).toFloat()).coerceAtMost(0.49f)
            else -> (bestCount + 1).toFloat() / (totalSamples + corrections + 2).toFloat()
        }
        return bestCat to confidence
    }

    // ===== 特征 + 贝叶斯 =====

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

    // ===== 冷启动决策表 — 基于日常生活时间+金额精确推断 =====

    private fun coldStartPredict(type: String, ts: Long, amount: Double, channel: String): Pair<String, Float>? {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        val dom = c.get(Calendar.DAY_OF_MONTH)
        val isWeekend = c.get(Calendar.DAY_OF_WEEK).let { it == 1 || it == 7 }
        val ch = channel.lowercase()
        val candidates = mutableListOf<Pair<String, Float>>()

        // === 收入冷启动 ===
        if (type != TransactionRecord.TYPE_EXPENSE) {
            when {
                dom in 8..20 && amount >= 3000 -> return "基本工资" to 0.35f
                dom in 8..20 && amount >= 1500 -> return "奖金提成" to 0.20f
                amount <= 1.0 -> return "收发红包" to 0.25f
                h in 9..18 && amount <= 500 -> return "转账退款" to 0.25f
                h in 20..23 || h in 0..4 -> return when {
                    amount <= 10 -> "收发红包" to 0.30f
                    amount <= 100 -> "收发红包" to 0.20f
                    else -> null
                }
            }
            return null
        }

        // 将小时转换为分钟时间戳方便精确判断
        val timeMin = h * 60 + m

        // === 早通勤 6:30-9:30（工作日）===
        if (timeMin in 390..570 && !isWeekend) {
            when {
                amount <= 3 -> { candidates.add("公交" to 0.55f) }
                amount <= 8 -> { candidates.add("公交" to 0.45f); candidates.add("地铁" to 0.25f) }
                amount <= 15 -> { candidates.add("地铁" to 0.45f); candidates.add("公交" to 0.20f); candidates.add("早餐" to 0.15f) }
                amount <= 30 -> { candidates.add("早餐" to 0.40f); candidates.add("地铁" to 0.15f) }
                amount <= 60 -> { candidates.add("早餐" to 0.30f); candidates.add("外卖" to 0.10f) }
                else -> { candidates.add("日用品" to 0.12f) }
            }
        }
        // === 早通勤 6:30-9:30（周末）===
        else if (timeMin in 390..570 && isWeekend) {
            when {
                amount <= 10 -> candidates.add("零食饮料" to 0.25f)
                amount <= 30 -> candidates.add("早餐" to 0.30f)
                amount <= 100 -> candidates.add("日用品" to 0.15f)
            }
        }
        // === 上午工作时段 9:30-10:30 ===
        else if (timeMin in 570..630) {
            when {
                amount <= 20 -> candidates.add("零食饮料" to 0.35f)
                amount <= 100 -> candidates.add("日用品" to 0.15f)
                else -> candidates.add("数码" to 0.10f)
            }
        }
        // === 午餐前 10:30-11:00 ===
        else if (timeMin in 630..660) {
            when {
                amount <= 15 -> candidates.add("零食饮料" to 0.25f)
                amount <= 50 -> candidates.add("外卖" to 0.30f)
            }
        }
        // === 午餐 11:00-13:30 ===
        else if (timeMin in 660..810) {
            when {
                amount <= 8 -> { candidates.add("食堂" to 0.50f) }
                amount <= 20 -> { candidates.add("食堂" to 0.30f); candidates.add("外卖" to 0.28f) }
                amount <= 35 -> { candidates.add("外卖" to 0.38f); candidates.add("食堂" to 0.18f); candidates.add("下馆子" to 0.10f) }
                amount <= 60 -> { candidates.add("外卖" to 0.25f); candidates.add("下馆子" to 0.28f) }
                amount <= 150 -> { candidates.add("下馆子" to 0.35f); candidates.add("外卖" to 0.10f) }
                amount <= 500 -> { candidates.add("下馆子" to 0.20f); candidates.add("日用品" to 0.10f) }
                else -> { candidates.add("日用品" to 0.12f) }
            }
        }
        // === 下午 13:30-17:00 ===
        else if (timeMin in 810..1020) {
            when {
                amount <= 15 -> candidates.add("零食饮料" to 0.35f)
                amount <= 40 -> { candidates.add("零食饮料" to 0.20f); candidates.add("日用品" to 0.15f) }
                amount <= 150 -> { candidates.add("日用品" to 0.20f); candidates.add("服饰" to 0.10f) }
                amount <= 500 -> { candidates.add("服饰" to 0.15f); candidates.add("数码" to 0.10f) }
                else -> candidates.add("数码" to 0.12f)
            }
        }
        // === 晚通勤+晚餐 17:00-20:00 ===
        else if (timeMin in 1020..1200) {
            // 通勤部分
            if (!isWeekend && amount <= 15 && timeMin in 1020..1110) {
                candidates.add("地铁" to 0.35f); candidates.add("公交" to 0.20f)
            }
            when {
                amount <= 15 -> { candidates.add("零食饮料" to 0.30f); candidates.add("外卖" to 0.10f) }
                amount <= 35 -> { candidates.add("外卖" to 0.30f); candidates.add("食堂" to 0.15f) }
                amount <= 80 -> { candidates.add("外卖" to 0.20f); candidates.add("下馆子" to 0.30f) }
                amount <= 200 -> { candidates.add("下馆子" to 0.35f); candidates.add("外卖" to 0.08f) }
                amount <= 500 -> { candidates.add("下馆子" to 0.20f); candidates.add("日用品" to 0.12f) }
                else -> { candidates.add("日用品" to 0.10f) }
            }
        }
        // === 晚间 20:00-22:00 ===
        else if (timeMin in 1200..1320) {
            when {
                amount <= 25 -> candidates.add("零食饮料" to 0.30f)
                amount <= 80 -> { candidates.add("日用品" to 0.15f); candidates.add("游戏充值" to 0.12f) }
                amount <= 300 -> { candidates.add("服饰" to 0.12f); candidates.add("数码" to 0.10f) }
                else -> candidates.add("数码" to 0.10f)
            }
        }
        // === 深夜 22:00-2:00 ===
        else if (timeMin in 1320..1440 || timeMin in 0..120) {
            when {
                amount <= 25 -> candidates.add("零食饮料" to 0.32f)
                amount <= 80 -> { candidates.add("零食饮料" to 0.20f); candidates.add("游戏充值" to 0.18f) }
                amount <= 200 -> { candidates.add("下馆子" to 0.22f); candidates.add("游戏充值" to 0.10f) }
                else -> { candidates.add("游戏充值" to 0.15f); candidates.add("数码" to 0.10f) }
            }
        }
        // === 凌晨 2:00-6:30 ===
        else if (timeMin in 120..390) {
            when {
                amount <= 50 -> candidates.add("零食饮料" to 0.20f)
                else -> candidates.add("其他" to 0.10f)
            }
        }

        // === 渠道加权（强信号覆盖） ===
        when {
            ch.contains("美团") || ch.contains("饿了么") -> candidates.add("外卖" to 0.45f)
            ch.contains("滴滴") || ch.contains("打车") -> candidates.add("打车" to 0.45f)
            ch.contains("京东") || ch.contains("jd") -> candidates.add("数码" to 0.30f)
            ch.contains("淘宝") || ch.contains("天猫") -> {
                candidates.add("日用品" to 0.22f)
                if (amount > 100) candidates.add("服饰" to 0.14f)
            }
        }

        // === 大额推断 ===
        when {
            amount >= 3000 -> { candidates.add("房租" to 0.25f); candidates.add("数码" to 0.08f) }
            amount >= 1000 -> { candidates.add("房租" to 0.15f); candidates.add("数码" to 0.12f) }
            amount >= 500 -> { candidates.add("数码" to 0.14f); candidates.add("服饰" to 0.10f) }
        }

        // === 月初规则（房租/贷款高频时段） ===
        if (dom in 1..7) {
            when {
                amount >= 2500 && amount <= 8000 -> candidates.add("房租" to 0.40f)
                amount >= 1000 && amount <= 2500 -> candidates.add("房租" to 0.22f)
            }
        }
        // === 月末规则（水电/物业/信用卡还款） ===
        if (dom in 20..28) {
            when {
                amount in 50.0..500.0 && (ch.contains("95598") || ch.contains("电力") || ch.contains("电费")) ->
                    candidates.add("水电燃气" to 0.35f)
                amount in 100.0..2000.0 && ch.contains("信用卡") ->
                    candidates.add("其他" to 0.20f)
                amount in 100.0..1000.0 -> candidates.add("水电燃气" to 0.12f)
            }
        }
        // === 金额特征规则（心理定价 → 网购） ===
        when {
            amount < 0.1 -> candidates.add("收发红包" to 0.25f)
            amount in 9.8..9.99 || amount in 19.8..19.99 || amount in 29.8..29.99 ->
                candidates.add("日用品" to 0.15f)
            amount in 99.0..99.99 || amount in 199.0..199.99 ->
                candidates.add("服饰" to 0.12f)
            amount in 299.0..299.99 || amount in 599.0..599.99 ->
                candidates.add("服饰" to 0.10f)
        }

        // === 节日/购物节规则 ===
        val month = c.get(Calendar.MONTH)
        when {
            dom == 18 && month == Calendar.JUNE -> candidates.add("日用品" to 0.20f) // 618
            dom == 11 && month == Calendar.NOVEMBER -> { candidates.add("日用品" to 0.22f); candidates.add("服饰" to 0.15f) } // 11.11
            dom == 12 && month == Calendar.DECEMBER -> candidates.add("日用品" to 0.15f) // 12.12
            month == Calendar.JANUARY || month == Calendar.FEBRUARY -> if (dom >= 20) candidates.add("旅行出游" to 0.12f) // 春运
            month == Calendar.OCTOBER && dom in 1..7 -> candidates.add("旅行出游" to 0.15f) // 国庆
            month == Calendar.SEPTEMBER && dom == 10 -> candidates.add("服饰" to 0.12f) // 教师节
            month == Calendar.MAY && dom in 8..12 -> candidates.add("服饰" to 0.10f) // 母亲节
        }

        // === 支付方式偏移 ===
        if (ch.contains("花呗") || ch.contains("白条") || ch.contains("信用卡")) {
            candidates.add("日用品" to 0.12f)
            if (amount > 200) candidates.add("服饰" to 0.10f)
            if (amount > 1000) candidates.add("数码" to 0.10f)
        }

        // 合并同类项取最大概率
        val merged = candidates.groupBy { it.first }
            .mapValues { (_, list) -> list.maxOf { it.second } }
        val best = merged.maxByOrNull { it.value }
        return if (best != null && best.value >= 0.10f) best.toPair() else null
    }

    // ===== 规则引擎 =====

    private fun rulePredict(type: String, ts: Long, amount: Double, channel: String, merchant: String?, rawText: String?, locationName: String? = null): String? {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val combined = ((merchant.orEmpty()) + " " + channel + " " + (rawText.orEmpty()) + " " + (locationName.orEmpty())).lowercase()
        val ch = channel.lowercase()
        val mRaw = (merchant.orEmpty()).lowercase()
        val loc = (locationName.orEmpty()).lowercase()
        val amt = amount

        fun has(vararg kw: String) = kw.any { combined.contains(it) }
        fun chHas(vararg kw: String) = kw.any { ch.contains(it) }
        fun mHas(vararg kw: String) = kw.any { mRaw.contains(it) }
        fun locHas(vararg kw: String) = kw.any { loc.contains(it) }

        // ===== TIER 0: 地理位置信号（最强优先级） =====
        if (type != TransactionRecord.TYPE_INCOME) {
            if (locHas("地铁", "metro", "轨道交通")) return "地铁"
            if (locHas("公交", "巴士", "brt")) return "公交"
            if (locHas("加油", "石化", "石油", "壳牌", "加油站")) return "加油停车"
            if (locHas("停车", "车库", "停车场")) return "加油停车"
            if (locHas("医院", "诊所", "门诊", "卫生", "体检")) return "挂号门诊"
            if (locHas("药店", "药房", "大药房", "健之佳", "大参林")) return "药品"
            if (locHas("超市", "沃尔玛", "家乐福", "永辉", "盒马", "大润发", "costco")) return "超市日用"
            if (locHas("便利店", "711", "全家", "罗森", "便利")) return "零食饮料"
            if (locHas("咖啡", "星巴克", "瑞幸", "manner", "tea", "茶")) return "零食饮料"
            if (locHas("餐厅", "饭店", "美食", "酒楼", "食府", "小吃", "排档")) return "下馆子"
            if (locHas("商场", "广场", "购物", "百货", "奥莱", "outlets")) return "服饰"
            if (locHas("学校", "大学", "学院", "培训", "教育")) return "课程培训"
            if (locHas("书店", "图书", "书城")) return "书籍文具"
            if (locHas("影院", "电影", "剧院", "大麦", "演出")) return "电影演出"
            if (locHas("酒店", "旅馆", "民宿", "宾馆")) return "旅行出游"
            if (locHas("健身", " gym", "瑜伽", "游泳", "运动")) return "娱乐"
            if (locHas("理发", "美容", "美甲", "美发", "沙龙")) return "日用品"
            if (locHas("菜市场", "市场", "农贸")) return "超市日用"
            if (locHas("银行", "atm")) return "其他"
            if (locHas("充电", "充电桩", "新能源")) return "加油停车"
        }

        if (type != TransactionRecord.TYPE_INCOME) {

            // ===== TIER 1: 强商户信号 =====

            if (has("美团", "饿了吗", "饿了么", "外卖", "keeta", "kika", "配送费", "骑手")) return "外卖"

            if (has("滴滴", "打车", "出租", "网约", "t3", "曹操", "首汽", "哈啰出行", "花小猪", "如祺", "嘀嗒")) return "打车"
            if (has("地铁", "metro", "乘车码", "乘车")) return "地铁"
            if (has("公交", "巴士", "brt")) return "公交"
            if (has("共享单车", "美团单车", "哈啰单车", "青桔", "bike", "骑行")) return "公交"
            if (has("高铁", "火车", "12306", "铁路", "机票", "航班", "航空", "携程", "去哪儿", "飞猪", "同程")) return "火车机票"
            if (has("加油", "中石化", "中石油", "壳牌", "加油站", "中化石油")) return "加油停车"
            if (has("停车费", "停车场", "停车")) return "加油停车"

            // 地图/导航 → 打车
            if (has("高德", "百度地图", "腾讯地图", "导航", "a map")) return "打车"
            // 即时配送 → 外卖
            if (has("闪送", "达达", "跑腿", "同城急送", "顺丰同城", "美团跑腿")) return "外卖"
            // 快递 → 其他
            if (has("顺丰", "圆通", "中通", "申通", "韵达", "ems", "邮政快递", "京东物流", "京东快递")) return "其他"
            // 还款/分期 → 其他
            if (has("花呗还款", "借呗还款", "信用还款", "分期还款", "信用卡还款", "账单还款", "自动还款")) return "其他"

            if (has("肯德基", "kfc", "麦当劳", "汉堡王", "必胜客", "德克士", "华莱士", "萨莉亚", "吉野家", "真功夫")) return "下馆子"
            if (has("海底捞", "呷哺", "火锅", "烧烤", "串串", "烤肉", "日料", "韩餐", "西餐", "寿司", "刺身")) return "下馆子"
            if (has("星巴克", "瑞幸", "咖啡", "manner", "costa", "tim hortons", "seesaw")) return "零食饮料"
            if (has("奶茶", "喜茶", "蜜雪", "茶颜", "库迪", "coco", "一点点", "茶百道", "书亦", "古茗", "益禾堂", "甜啦啦")) return "零食饮料"

            if (has("食堂", "公司餐", "员工餐")) return "食堂"
            if (has("下馆子", "聚餐", "餐厅", "饭店", "美食", "小吃", "排档")) return "下馆子"
            if (has("零食", "面包", "蛋糕", "甜品", "便利店", "711", "全家", "罗森", "便利")) return "零食饮料"

            if (has("超市", "永辉", "沃尔玛", "家乐福", "盒马", "大润发", "物美", "华润万家", "山姆", "costco", "麦德龙")) return "超市日用"

            if (has("话费", "移动", "联通", "电信", "运营商")) return "话费"
            if (has("流量包", "数据包", "流量")) return "流量"

            if (has("房租", "租金", "押金")) return "房租"
            if (has("电费", "水费", "燃气", "暖气", "供暖")) return "水电燃气"
            if (has("物业", "宽带", "网费", "wifi")) return "物业宽带"

            if (has("药", "药店", "医院", "挂号", "门诊", "诊所", "体检", "齿科", "眼科", "牙科")) return "挂号门诊"
            if (has("处方", "胶囊", "感冒药", "退烧", "膏药", "维生素", "健之佳", "大参林", "益丰")) return "药品"

            if (has("电影", "演出", "票务", "影院", "猫眼", "大麦", "淘票票")) return "电影演出"
            if (has("游戏", "steam", "psn", "nintendo", "switch", "xbox", "原神", "王者", "腾讯视频", "爱奇艺", "b站", "bilibili", "优酷", "网易云", "qq音乐", "酷狗", "酷我", "spotify", "netflix")) return "游戏充值"
            if (has("旅行", "旅游", "酒店", "民宿", "飞猪", "途家", "airbnb", "门票", "景点", "乐园", "迪士尼", "方特")) return "旅行出游"
            if (has("健身", "健身房", "瑜伽", "游泳", "运动", "keep", "超级猩猩")) return "娱乐"

            // 数字订阅服务
            if (has("icloud", "apple music", "app store", "google play", "apple.com/bill", "apple 订阅")) return "游戏充值"
            if (has("youtube", "netflix", "spotify", "disney", "hbo", "amazon prime")) return "游戏充值"
            // 短视频/直播打赏
            if (has("抖音", "douyin", "快手", "kuaishou", "直播", "打赏", "钻石", "抖币", "快币")) return "游戏充值"

            if (has("课程", "培训", "学费", "网课", "得到", "极客", "知识付费", "知乎", "樊登", "混沌")) return "课程培训"
            if (has("书籍", "文具", "教材", "kindle", "当当", "图书")) return "书籍文具"

            // ===== TIER 2: 网购平台细分 =====

            if (chHas("淘宝", "天猫") || mHas("淘宝", "天猫")) return when {
                has("服饰", "衣服", "鞋", "包", "服装", "裙", "外套", "裤", "内衣", "羽绒服", "卫衣", "衬衫") -> "服饰"
                has("数码", "电子", "手机", "电脑", "耳机", "充电", "配件", "键盘", "鼠标", "平板", "显示器") -> "数码"
                has("美妆", "护肤", "化妆", "面膜", "口红", "粉底", "精华", "乳液", "防晒", "香水") -> "美妆护肤"
                has("食品", "零食", "水果", "生鲜", "大米", "牛奶", "饮料") -> "超市日用"
                amt >= 500 -> "数码"
                amt >= 100 -> "服饰"
                else -> "日用品"
            }
            if (chHas("京东", "jd") || mHas("京东")) return when {
                has("数码", "电子", "手机", "电脑", "家电", "电器", "耳机", "电视", "冰箱", "洗衣机", "空调") -> "数码"
                has("服饰", "衣服", "鞋") -> "服饰"
                has("食品", "生鲜", "水果", "牛奶") -> "超市日用"
                has("美妆", "护肤", "化妆") -> "美妆护肤"
                else -> "数码"
            }
            if (chHas("拼多多") || mHas("拼多多")) return "日用品"
            if (chHas("苏宁") || mHas("苏宁")) return "数码"

            if (chHas("得物") || mHas("得物") || mHas("poizon")) return "服饰"
            if (chHas("小红书") || mHas("小红书")) return "美妆护肤"
            if (chHas("唯品会") || mHas("唯品会")) return "服饰"
            if (chHas("网易严选") || mHas("网易严选")) return "日用品"
            if (chHas("抖音") || chHas("douyin") || mHas("抖音")) return "日用品"
            if (chHas("快手") || mHas("快手")) return "日用品"
            if (chHas("微店") || mHas("微店")) return "日用品"
            if (chHas("转转") || mHas("转转") || mHas("闲鱼")) return "其他"

            if (has("红包")) return "收发红包"
            if (has("转账", "转给")) return "其他"

            // ===== TIER 3: 渠道 + 金额 + 时段 =====

            if (chHas("京东", "jd")) return "数码"
            if (chHas("淘宝", "天猫")) return "日用品"

            if (h in 6..9 && amt < 20) return "公交"
            if (h in 11..13) return when {
                amt < 15 -> "食堂"
                amt < 40 -> "外卖"
                amt < 200 -> "下馆子"
                else -> null
            }
            if (h in 17..20) return when {
                amt < 15 -> "零食饮料"
                amt < 200 -> "下馆子"
                else -> null
            }
            if (h in 21..23 || h in 0..4) return when {
                amt < 30 -> "零食饮料"
                amt < 100 -> "游戏充值"
                else -> null
            }
            if (h in 9..11 && amt in 30.0..300.0) return "日用品"
            if (h in 14..16 && amt < 30) return "零食饮料"

            return null
        }

        return when {
            has("工资", "薪", "薪资", "代发") -> "基本工资"
            has("奖金", "提成", "绩效") -> "奖金提成"
            has("利息", "分红", "收益", "理财收益") -> "利息分红"
            has("基金", "股票", "理财", "余额宝", "定期") -> "基金股票"
            has("红包") -> "收发红包"
            has("退款", "退回", "退货") -> "转账退款"
            else -> null
        }
    }

    private fun defaultCategory(type: String): String =
        if (type == TransactionRecord.TYPE_EXPENSE) "其他" else "其他收入"
}
