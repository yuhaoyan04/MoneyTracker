package com.mudasir.smartledger.util

import com.mudasir.smartledger.data.TransactionRecord
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * 微信 / 支付宝官方账单解析器 —— 自动抓取的兜底与对账主链路。
 *
 * 支持：
 * - 微信新版导出的 XLSX（ZIP+XML 结构，用标准库解压解析，无第三方依赖）
 * - 微信/支付宝 CSV（GBK 自动回退、逗号或 Tab 分隔、引号转义、顶部说明行跳过）
 * - 微信表头行探测：账单顶部十几行元数据中也有「交易时间：」字样，
 *   必须用多关键词组合（时间 + 收/支 + 金额）锁定真正的表头
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
        if (bytes.isEmpty()) return Result.Failure("文件为空或无法读取")
        // ZIP 魔数 PK —— 微信新版账单为 XLSX
        return if (bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            val table = runCatching { extractXlsxTable(bytes) }.getOrElse {
                return Result.Failure("无法读取 XLSX 账单（文件可能已损坏）")
            }
            if (table.isEmpty()) return Result.Failure("XLSX 账单为空")
            parseTable(table)
        } else {
            val text = decodeText(bytes) ?: return Result.Failure("文件为空或无法读取")
            val logicalLines = splitLogicalLines(text)
            if (logicalLines.size < 2) return Result.Failure("文件内容为空")
            val headerIdx = logicalLines.indexOfFirst { isHeaderRow(it) }
                ?: return Result.Failure("未找到账单表头——请选择微信/支付宝导出的原始 CSV 或 XLSX 账单（未经编辑）")
            // 微信官方导出为逗号分隔；从表格软件复制/另存的版本常为 Tab 分隔，自动适配
            val delimiter = if (logicalLines[headerIdx].contains('\t')) '\t' else ','
            parseTable(logicalLines.map { splitCsvLine(it, delimiter) })
        }
    }

    // ===== 表格解析（CSV 与 XLSX 共用） =====

    private fun parseTable(table: List<List<String>>): Result {
        val headerIdx = table.indexOfFirst { row -> isHeaderRow(row.joinToString(" ")) }
        if (headerIdx < 0) {
            return Result.Failure("未找到账单表头——请选择微信/支付宝导出的原始 CSV 或 XLSX 账单（未经编辑）")
        }
        val header = table[headerIdx].map { it.trim() }

        fun col(names: List<String>): Int =
            header.indexOfFirst { h -> names.any { h.contains(it) } }

        val format = when {
            header.any { it.contains("交易单号") } -> Format.WECHAT
            header.any { it.contains("交易号") } -> Format.ALIPAY
            else -> return Result.Failure("无法识别账单格式（既不是微信也不是支付宝账单）")
        }

        val idxTime: Int
        val idxAmount: Int
        val idxDir: Int
        val idxMerchant: Int
        val idxItem: Int
        val idxPayMethod: Int
        val idxStatus: Int
        when (format) {
            Format.WECHAT -> {
                idxTime = col(listOf("交易时间"))
                idxAmount = col(listOf("金额"))
                idxDir = col(listOf("收/支"))
                idxMerchant = col(listOf("交易对方"))
                idxItem = col(listOf("商品"))
                idxPayMethod = col(listOf("支付方式"))
                idxStatus = col(listOf("当前状态"))
            }
            Format.ALIPAY -> {
                idxTime = minPositive(col(listOf("付款时间")), col(listOf("交易创建时间")))
                idxAmount = col(listOf("金额"))
                idxDir = col(listOf("收/支"))
                idxMerchant = col(listOf("交易对方"))
                idxItem = col(listOf("商品名称"))
                idxPayMethod = col(listOf("付款方式", "支付方式", "收/付款方式"))
                idxStatus = col(listOf("交易状态"))
            }
        }
        if (idxTime < 0 || idxAmount < 0 || idxDir < 0) {
            return Result.Failure("账单表头缺少关键列（时间/金额/收支柱）")
        }

        val sourceChannel = if (format == Format.WECHAT) "微信支付" else "支付宝"
        val bills = mutableListOf<ParsedBill>()
        var skipped = 0

        for (i in headerIdx + 1 until table.size) {
            val cells = table[i]
            if (cells.size < 3) { skipped++; continue }
            try {
                val dirRaw = cells.getOrNull(idxDir).orEmpty().trim()
                val type = when {
                    dirRaw.startsWith("支出") -> TransactionRecord.TYPE_EXPENSE
                    dirRaw.startsWith("收入") -> TransactionRecord.TYPE_INCOME
                    else -> { skipped++; continue }  // 不计收支（"/"、不计收支等中性交易）
                }
                val amount = cells.getOrNull(idxAmount).orEmpty()
                    .replace("¥", "").replace("￥", "").trim().toDoubleOrNull()
                if (amount == null || amount <= 0.0) { skipped++; continue }

                val timeRaw = cells.getOrNull(idxTime).orEmpty().trim()
                var ts = parseTime(timeRaw)
                if (ts <= 0L) {
                    // XLSX 中时间可能存为 Excel 日期序列数
                    val serial = timeRaw.toDoubleOrNull()
                    if (serial != null) ts = excelSerialToMillis(serial)
                }
                if (ts <= 0L) { skipped++; continue }

                // 支付宝只导已成功的交易；微信「当前状态」宽松处理（收/支列已指明方向，退款行会被记为收入）
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

    /** 微信表头行：时间 + 收/支 + 金额 同时出现（顶部说明行只有「交易时间：」单一关键词）。 */
    private fun isHeaderRow(joined: String): Boolean =
        (joined.contains("交易时间") || joined.contains("交易创建时间")) &&
            joined.contains("收/支") && joined.contains("金额")

    // ===== XLSX（ZIP + XML）解析 =====

    private fun extractXlsxTable(bytes: ByteArray): List<List<String>> {
        val shared = mutableListOf<String>()
        val sheets = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.trimStart('/')
                when {
                    name.equals("xl/sharedStrings.xml", true) ->
                        shared.addAll(parseSharedStrings(zis.readBytes()))
                    name.startsWith("xl/worksheets/") && name.endsWith(".xml") ->
                        sheets.add(name to zis.readBytes())
                }
                entry = zis.nextEntry
            }
        }

        // 优先取包含表头关键词的 sheet，否则退回第一个
        val parsedSheets = sheets.map { (_, xml) -> parseSheet(xml, shared) }
        return parsedSheets.firstOrNull { rows -> rows.any { isHeaderRow(it.joinToString(" ")) } }
            ?: parsedSheets.firstOrNull()
            ?: emptyList()
    }

    private fun parseSheet(xmlBytes: ByteArray, shared: List<String>): List<List<String>> {
        val xml = xmlBytes.toString(Charsets.UTF_8)
        val rows = mutableListOf<List<String>>()
        Regex("<row[^>]*>.*?</row>|<row[^>]*/>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { rowM ->
            val cells = mutableMapOf<Int, String>()
            Regex("<c\\s[^>]*>.*?</c>|<c\\s[^>]*/>", RegexOption.DOT_MATCHES_ALL).findAll(rowM.value).forEach { cellM ->
                val cellXml = cellM.value
                val ref = Regex("\\sr=\"([A-Z]+)\\d+\"").find(cellXml)?.groupValues?.get(1) ?: return@forEach
                val colIdx = colIndex(ref)
                val value = when {
                    cellXml.contains("t=\"s\"") -> {
                        val v = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(cellXml)?.groupValues?.get(1)
                        v?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    }
                    cellXml.contains("t=\"inlineStr\"") ->
                        Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                            .findAll(cellXml).joinToString("") { unescapeXml(it.groupValues[1]) }
                    else -> unescapeXml(
                        Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
                            .find(cellXml)?.groupValues?.get(1).orEmpty()
                    )
                }
                cells[colIdx] = value
            }
            if (cells.isNotEmpty()) {
                val maxCol = cells.keys.max()
                rows.add((0..maxCol).map { cells[it].orEmpty() })
            }
        }
        return rows
    }

    private fun parseSharedStrings(xmlBytes: ByteArray): List<String> {
        val xml = xmlBytes.toString(Charsets.UTF_8)
        val out = mutableListOf<String>()
        Regex("<si[ >].*?</si>|<si/>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
            val text = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                .findAll(m.value).joinToString("") { unescapeXml(it.groupValues[1]) }
            out.add(text)
        }
        return out
    }

    private fun colIndex(letters: String): Int {
        var idx = 0
        for (ch in letters) idx = idx * 26 + (ch - 'A' + 1)
        return idx - 1
    }

    private fun unescapeXml(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toInt(16).toChar().toString() }
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toInt().toChar().toString() }
        .replace("&amp;", "&")

    /** Excel 日期序列（1900 日期系统，纪元 1899-12-30）→ 毫秒。 */
    private fun excelSerialToMillis(serial: Double): Long = runCatching {
        val cal = Calendar.getInstance().apply { clear() }
        cal.set(1899, Calendar.DECEMBER, 30, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val days = serial.toLong()
        cal.add(Calendar.DAY_OF_YEAR, days.toInt())
        cal.add(Calendar.MILLISECOND, ((serial - days) * 86400000.0).toInt())
        cal.timeInMillis
    }.getOrDefault(0L)

    private fun minPositive(a: Int, b: Int): Int = when {
        a < 0 -> b
        b < 0 -> a
        else -> minOf(a, b)
    }

    /** UTF-8 优先；出现替换符则回退 GBK（微信/支付宝 CSV 默认 GBK）。 */
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

    /** 单行 CSV 分列（处理 "" 转义；分隔符可为逗号或 Tab）。 */
    private fun splitCsvLine(line: String, delimiter: Char = ','): List<String> {
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
                c == delimiter && !inQuote -> { out.add(sb.toString().trim()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString().trim())
        return out
    }

    private fun parseTime(raw: String): Long = runCatching {
        val clean = raw.substringBefore('.').trim()
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .apply { isLenient = false }.parse(clean)?.time ?: 0L
    }.getOrDefault(0L)
}
