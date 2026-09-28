package com.mudasir.smartledger.util

import com.mudasir.smartledger.data.Electricity
import com.mudasir.smartledger.data.Expense
import com.mudasir.smartledger.data.MilkRecord
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.util.AiSettings.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 洞察助手。
 *
 * 已改造为运行时配置（[AiSettings]）+ OpenAI 兼容客户端（[DeepSeekClient]），
 * 支持 DeepSeek / GLM / Kimi 等，API Key 在应用内输入，无需重新编译打包。
 */
object AiHelper {

    fun summarizeElectricity(records: List<Electricity>): String {
        if (records.isEmpty()) return "No records."
        val sdf = SimpleDateFormat("MMM yyyy", Locale.getDefault())
        return records.joinToString("; ") {
            "Date: ${sdf.format(Date(it.endDate))}, Units: ${it.totalUnits ?: 0}, Rs: ${it.amount ?: 0}"
        }
    }

    fun summarizeMilk(records: List<MilkRecord>): String {
        if (records.isEmpty()) return "No records."
        val sortedRecords = records.sortedWith(compareBy({ it.year }, { it.monthIndex }))
        return sortedRecords.joinToString("; ") {
            "Period: ${it.monthName} ${it.year}, Qty: ${it.totalLiters}L, Rate: Rs ${it.pricePerLiter}/L, Total: Rs ${it.totalAmount}"
        }
    }

    fun summarizeExpenses(records: List<Expense>): String {
        if (records.isEmpty()) return "No records."
        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        return records.joinToString("; ") {
            "[Date: ${sdf.format(Date(it.date))}, Item: ${it.title}, Info: ${it.description}, Rs: ${it.amount}]"
        }
    }

    /** 统一交易记录摘要，供新统计/AI 洞察使用。 */
    fun summarizeTransactions(records: List<TransactionRecord>): String {
        if (records.isEmpty()) return "暂无记录。"
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return records.take(120).joinToString("; ") { r ->
            "[${sdf.format(Date(r.timestamp))} ${r.type} ${r.channelName} " +
                "${r.categoryName.ifBlank { "未分类" }} ${r.merchant ?: ""} ${r.amount}]"
        }
    }

    fun isError(result: String): Boolean {
        val r = result.lowercase()
        return r.contains("internet") || r.contains("network") ||
            r.contains("limit") || r.contains("overloaded") ||
            r.contains("busy") || r.contains("unavailable") ||
            r.contains("failed") || r.contains("无网络") ||
            r.contains("认证失败") || r.contains("不可用") ||
            r.contains("额度") || r.contains("配置")
    }

    private suspend fun callLedgerAi(prompt: String, config: Config): String {
        if (!config.isReady) return "AI 未配置：请先在设置中填写 API Key。"
        return try {
            DeepSeekClient.complete(config, prompt)
        } catch (e: IllegalStateException) {
            e.message ?: "AI 请求失败。"
        }
    }

    suspend fun getInsight(dataType: String, dataSummary: String, config: Config): String {
        val currentDate = SimpleDateFormat("yyyy 年 MM 月 dd 日", Locale.getDefault()).format(Date())
        val cleanSummary = dataSummary
            .replace(Regex("\\[Last completed month:.*?\\]"), "")
            .trim()

        val domainContext = when (dataType.lowercase()) {
            "electricity" -> "领域：电费与能耗账单。关注用电量(kWh)、季节性趋势、单位成本与节能。"
            "milk" -> "领域：家庭牛奶消费与支出。关注月度量(升)、单价、消费一致性。"
            "transactions" -> "领域：个人综合收支。关注支出在各分类的分布、固定与可变支出占比、预算纪律。"
            else -> "领域：个人与家庭支出。关注分类分布、经常性支出与可自由支配支出。"
        }

        val prompt = """
        当前日期：$currentDate。
        $domainContext
        角色：资深财务分析师与家庭预算顾问。
        历史记录：$cleanSummary

        任务：
        1. 趋势分析：基于记录数据，简明分析 $dataType 的历史收支/消费模式。
        2. 异常与波动：指出成本、单价或数量上的显著变动、峰值或回落。
        3. 优化建议：给出一条切实可行的优化或省钱建议。

        关键规则：
        - 仅基于上述历史数据分析，不要做未来预测。
        - 使用人民币「元」与清晰单位。
        - 输出 3-4 条要点，纯文本，不要使用 **、*、# 等 markdown 符号。
    """.trimIndent()

        return callLedgerAi(prompt, config)
    }

    suspend fun getPrediction(dataType: String, dataSummary: String, config: Config): String {
        val currentDate = SimpleDateFormat("yyyy 年 MM 月", Locale.getDefault()).format(Date())
        val predictMonthMatch = Regex("Predict for: (.+?) only\\.").find(dataSummary)
        val predictMonth = predictMonthMatch?.groupValues?.get(1) ?: "下个月"
        val cleanSummary = dataSummary
            .replace(Regex("\\[Last completed month:.*?\\]"), "")
            .trim()

        val domainContext = when (dataType.lowercase()) {
            "electricity" -> "领域：电费预测。考虑季节性能耗与历史账单趋势。"
            "milk" -> "领域：牛奶消费与成本预测。基于日常消费习惯与近期单价。"
            "transactions" -> "领域：综合收支预测。基于历史月均支出、固定支出与近期趋势。"
            else -> "领域：家庭支出预测。考虑历史月均支出与经常性支出。"
        }

        val prompt = """
        当前日期：$currentDate。
        $domainContext
        角色：资深财务预测专家。
        历史记录：$cleanSummary

        目标预测期：$predictMonth。

        任务：
        1. 以「预测：$predictMonth」开头。
        2. 预期趋势：基于历史与季节性，说明 $predictMonth 的预期方向。
        3. 基线对比：简要对比上一记录周期，指出预期变化。
        4. 预计金额：给出 $predictMonth 的人民币预测金额（如「预计金额：5200 元」）。
        5. 预计量（若适用）：给出预计数量（如「预计用量：60 升」或「150 度」）。

        格式：
        - 输出简明要点，纯文本。
        - 不要使用 **、*、# 等 markdown 符号。
        - 使用「元」与标准单位。
    """.trimIndent()

        return callLedgerAi(prompt, config)
    }

    fun formatAiResponse(text: String): String = text.replace(Regex("[#*]"), "").trim()
}
