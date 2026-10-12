package com.mudasir.smartledger.util

import com.mudasir.smartledger.data.TransactionRecord

/**
 * 微信 / 支付宝官方账单 CSV 解析器 —— 自动抓取的兜底与对账主链路。
 *
 * 覆盖通知监听天然无法覆盖的场景（付款码/扫一扫等前台支付时系统抑制通知）：
 * 用户从 微信支付→钱包→账单→下载账单 / 支付宝→我的→账单→开具交易流水
 * 导出 CSV，本解析器转换为交易记录，按指纹去重后入库。
 *
 * 格式要点：
 * - 微信：GBK 编码，表头含「交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态…」
 * - 支付宝：GBK 编码，表头含「交易号,…,交易创建时间,付款时间,…,交易对方,商品名称,金额（元）,收/支,交易状态…」
 * - 两者金额常带 ¥ 前缀、字段可能被引号包裹（含逗号/换行）
 */
object BillImportParser {

    data class ParsedBill(
        val timestamp: Long,
        val amount: Double,
        val type: String,
        val merchant: String?,
        val note: String?,
        val paymentMethod: String?,
        val dedupKey: String
    )

    sealed class Result {
        data class Success(
            val bills: List<ParsedBill>,
            val sourceChannel: String,
            val skippedRows: Int
        ) : Result()

        data class Failure(val reason: String) : Result()
    }

    private enum class Format { WECHAT, ALIPAY }

    fun parse(bytes: ByteArray): Result {
        val text = decodeText(bytes) ?: return Result.Failure("文件为空或无法读取")
        val logicalLines = splitLogicalLines(text)
        if (logicalLines.size < 2) return Result.Failure("文件内容为空")

        val headerIdx = logicalLines.indexOfFirst {
            it.contains("交易时间") || it.contains("交易创建时间")
        }
        if (headerIdx < 0) {
            return Result.Failure("未找到账单表头——请选择微信/支付宝导出的原始 CSV 账单（不要用 Excel 另存）")
        }
        val header = splitCsvLine(logicalLines[headerIdx]).map { it.trim() }
        val format = when {
            header.any { it.contains("交易单号") } -> Format.WECHAT
            header.any { it.contains("交易号") } -> Format.ALIPAY
            else -> return Result.Failure("无法识别账单格式（既不是微信也不是支付宝账单）")
        }

        fun col(names: List<String>): Int =
            header.indexOfFirst { h -> names.any { h.contains(it) } }

        val (idxTime, idxAmount, idxDir, idxMerchant, idxItem, idxPayMethod, idxStatus) = when (format) {
            Format.WECHAT -> Quattro(
                col(listOf("交易时间")),
                col(listOf("金额")),
                col(listOf("收/支")),
                col(listOf("交易对方")),
                col(listOf("商品")),
                col(listOf("支付方式")),
                col(listOf("当前状态"))
            )
            Format.ALIPAY -> Quattro(
                minPositive(col(listOf("付款时间")), col(listOf("交易创建时间"))),
                col(listOf("金额")),
                col(listOf("收/支")),
                col(listOf("交易对方")),
                col(listOf("商品名称")),
                col(listOf("付款方式")),
                col(listOf("交易状态"))
            )
        }
        if (idxTime < 0 || idxAmount < 0 || idxDir < 0) {
            return Result.Failure("账单表头缺少关键列（时间/金额/收支柱）")
        }

        val sourceChannel = if (format == Format.WECHAT) "微信支付" else "支付宝"
        val bills = mutableListOf<ParsedBill>()
        var skipped = 0

        for (i in headerIdx + 1 until logicalLines.size) {
            val cells = splitCsvLine(logicalLines[i])
            if (cells.size < 3) { skipped++; continue }
            try {
                val dirRaw = cells.getOrNull(idxDir).orEmpty().trim()
                val type = when {
                    dirRaw.startsWith("支出") -> TransactionRecord.TYPE_EXPENSE
                    dirRaw.startsWith("收入") -> TransactionRecord.TYPE_INCOME
                    else -> { skipped++; continue }  // 不计收支（"/"、不计收支等）
                }
                val amount = cells.getOrNull(idxAmount).orEmpty()
                    .replace("¥", "").replace("￥", "").trim().toDoubleOrNull()
                if (amount == null || amount <= 0.0) { skipped++; continue }

                val timeRaw = cells.getOrNull(idxTime).orEmpty().trim()
                val ts = parseTime(timeRaw)
                if (ts <= 0L) { skipped++; continue }

                // 支付宝只导已成功的交易；微信「当前状态」宽松处理（含退款时收/支列已指明方向）
                if (format == Format.ALIPAY) {
                    val status = cells.getOrNull(idxStatus).orEmpty()
                    if (status.isNotBlank() && !status.contains("成功")) { skipped++; continue }
                }

                val merchant = cells.getOrNull(idxMerchant)?.trim()?.takeIf { it.isNotBlank() && it != "/" }
                val note = cells.getOrNull(idxItem)?.trim()?.takeIf { it.isNotBlank() && it != "/" }
                val payMethod = cells.getOrNull(idxPayMethod)?.trim()?.takeIf { it.isNotBlank() && it != "/" }

                bills += ParsedBill(
                    timestamp = ts,
                    amount = amount,
                    type = type,
                    merchant = merchant,
                    note = note,
                    paymentMethod = payMethod,
                    dedupKey = "${type}|${"%.2f".format(amount)}|${ts / 1000}"
                )
            } catch (e: Exception) {
                skipped++
            }
        }

        if (bills.isEmpty()) return Result.Failure("账单中没有可导入的收支记录（共跳过 $skipped 行）")
        return Result.Success(bills, sourceChannel, skipped)
    }

    private data class Quattro(
        val time: Int, val amount: Int, val dir: Int,
        val merchant: Int, val item: Int, val payMethod: Int, val status: Int
    )

    private fun minPositive(a: Int, b: Int): Int = when {
        a < 0 -> b
        b < 0 -> a
        else -> minOf(a, b)
    }

    /** UTF-8 优先；出现替换符则回退 GBK（微信/支付宝账单默认 GBK）。 */
    private fun decodeText(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        return runCatching { String(bytes, charset("GBK")) }.getOrDefault(utf8)
    }

    /** 按字符流切分逻辑行（引号内的逗号与换行不分割）。 */
    private fun splitLogicalLines(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        for (c in text) {
            when {
                c == '"' -> { inQuote = !inQuote; sb.append(c) }
                (c == '\n') && !inQuote -> { out.add(sb.toString().trimEnd('\r')); sb.clear() }
                else -> sb.append(c)
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString().trimEnd('\r'))
        return out
    }

    /** 单行 CSV 分列（处理 "" 转义）。 */
    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuote && i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ }
                    else inQuote = !inQuote
                }
                c == ',' && !inQuote -> { out.add(sb.toString().trim()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString().trim())
        return out
    }

    private fun parseTime(raw: String): Long = runCatching {
        val clean = raw.substringBefore('.').trim()
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .apply { isLenient = false }.parse(clean)?.time ?: 0L
    }.getOrDefault(0L)
}
