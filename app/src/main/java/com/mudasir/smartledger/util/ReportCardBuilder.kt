package com.mudasir.smartledger.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.mudasir.smartledger.data.Category
import com.mudasir.smartledger.data.TransactionRecord
import kotlin.math.min

/**
 * 月报分享卡片：纯 Canvas 绘制品牌风格账单图（1080 宽，高度自适应）。
 * 布局：品牌头带 → 收支结余三列 → 支出构成 TOP5 条形 → 已省下/页脚。
 */
object ReportCardBuilder {

    private const val W = 1080
    private const val PAD = 64

    // 低饱和色板（与统计页一致）
    private val palette = intArrayOf(
        Color.parseColor("#C15F3C"), Color.parseColor("#6B8CAE"), Color.parseColor("#9C6B8F"),
        Color.parseColor("#94A374"), Color.parseColor("#CE9B4E"), Color.parseColor("#C07A8A"),
        Color.parseColor("#8C9BAB"), Color.parseColor("#55917F"), Color.parseColor("#6D7BB5"),
        Color.parseColor("#A8A29A")
    )
    private val rootColors = mapOf(
        "餐饮" to "#C15F3C", "交通" to "#6B8CAE", "网购" to "#9C6B8F", "日用" to "#94A374",
        "娱乐" to "#CE9B4E", "医疗" to "#C07A8A", "居住" to "#8C9BAB", "通讯" to "#55917F",
        "教育" to "#6D7BB5", "其他" to "#A8A29A"
    )

    fun build(
        year: Int,
        month: Int,
        monthLabel: String,
        records: List<TransactionRecord>,
        catByName: Map<String, Category>,
        savedTotal: Double,
        droppedCount: Int
    ): Bitmap {
        val income = records.filter { it.type == TransactionRecord.TYPE_INCOME }.sumOf { it.amount }
        val expense = records.filter { it.type == TransactionRecord.TYPE_EXPENSE }.sumOf { it.amount }
        val balance = income - expense

        val expenseRecs = records.filter { it.type == TransactionRecord.TYPE_EXPENSE }
        val byRoot = expenseRecs
            .groupBy { catByName[it.categoryName]?.parentName ?: it.categoryName }
            .map { (k, v) -> Triple(k, v.sumOf { it.amount }, v.size) }
            .sortedByDescending { it.second }
        val top = byRoot.take(5)

        // 高度自适应：头带 320 + 概览 260 + 标题 120 + top5*150 + 省下段 160 + 页脚 200
        val footerExtra = if (droppedCount > 0) 130 else 0
        val h = 320 + 260 + 130 + top.size * 150 + 60 + 160 + footerExtra + 120

        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#F5F4ED"))

        fun text(s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT): Paint {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = size
                this.textAlign = align
                if (bold) typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                isFakeBoldText = bold
            }
            c.drawText(s, x, y, p)
            return p
        }

        // ---- 头带 ----
        val header = Paint().apply { color = Color.parseColor("#C15F3C") }
        c.drawRect(0f, 0f, W.toFloat(), 320f, header)
        text("月度账单", PAD.toFloat(), 150f, 64f, Color.WHITE, bold = true)
        text(monthLabel, PAD.toFloat(), 220f, 44f, Color.parseColor("#F0D9CE"))
        text("MoneyTracker", (W - PAD).toFloat(), 150f, 36f, Color.parseColor("#CCFFFFFF"), align = Paint.Align.RIGHT)
        text("本地优先 · 智能记账", (W - PAD).toFloat(), 210f, 30f, Color.parseColor("#99FFFFFF"), align = Paint.Align.RIGHT)

        // ---- 三列概览 ----
        val ovY = 320f + 120f
        val colW = (W - PAD * 2) / 3f
        val cols = listOf(
            Triple("支出", expense, Color.parseColor("#B85450")),
            Triple("收入", income, Color.parseColor("#5C8A47")),
            Triple("结余", balance, Color.parseColor("#1A1A1A"))
        )
        cols.forEachIndexed { i, (label, v, color) ->
            val cx = PAD + colW * i + colW / 2
            text(label, cx, ovY, 34f, Color.parseColor("#6B6B6B"), align = Paint.Align.CENTER)
            text("¥${fmt(v)}", cx, ovY + 78f, 56f, color, bold = true, align = Paint.Align.CENTER)
        }

        // ---- 支出构成 ----
        var y = ovY + 170f
        c.drawRect(PAD.toFloat(), y, (W - PAD).toFloat(), y + 2f, Paint().apply { color = Color.parseColor("#E8E6E1") })
        y += 90f
        text("支出构成", PAD.toFloat(), y, 42f, Color.parseColor("#1A1A1A"), bold = true)
        y += 40f

        val maxAmt = top.maxOfOrNull { it.second } ?: 0.0
        top.forEachIndexed { i, (name, amt, cnt) ->
            y += 130f
            val color = Color.parseColor(rootColors[name] ?: String.format("#%06X", 0xFFFFFF and palette[i % palette.size]))
            text(name, PAD.toFloat(), y, 40f, Color.parseColor("#1A1A1A"), bold = true)
            text("${cnt}笔", (W - PAD) * 0.72f, y, 32f, Color.parseColor("#8A8A8A"), align = Paint.Align.RIGHT)
            text("¥${fmt(amt)}", (W - PAD).toFloat(), y, 40f, Color.parseColor("#1A1A1A"), bold = true, align = Paint.Align.RIGHT)
            // 条形
            y += 26f
            val barW = (W - PAD * 2).toFloat()
            val bgBar = Paint().apply { this.color = Color.parseColor("#E8E6E1") }
            c.drawRoundRect(RectF(PAD.toFloat(), y, PAD + barW, y + 20f), 10f, 10f, bgBar)
            val frac = if (maxAmt > 0) (amt / maxAmt).toFloat() else 0f
            val fgBar = Paint().apply { this.color = color }
            c.drawRoundRect(RectF(PAD.toFloat(), y, PAD + barW * frac, y + 20f), 10f, 10f, fgBar)
            val pct = if (expense > 0) (amt / expense * 100).toInt() else 0
            text("$pct%", PAD + barW + 8f, y + 20f, 30f, Color.parseColor("#6B6B6B"))
        }

        // ---- 已省下（想好再买）----
        y += 110f
        if (droppedCount > 0) {
            val box = Paint().apply { color = Color.parseColor("#EFF5EC") }
            c.drawRoundRect(RectF(PAD.toFloat(), y, (W - PAD).toFloat(), y + 130f), 24f, 24f, box)
            text("🧘 已省下 ¥${fmt(savedTotal)}", PAD + 36f, y + 58f, 40f, Color.parseColor("#5C8A47"), bold = true)
            text("想好再买拦下 $droppedCount 个冲动", PAD + 36f, y + 104f, 30f, Color.parseColor("#6B6B6B"))
            y += 170f
        }

        // ---- 页脚 ----
        y = h - 110f
        c.drawRect(PAD.toFloat(), y - 40f, (W - PAD).toFloat(), y - 38f, Paint().apply { color = Color.parseColor("#E8E6E1") })
        text("记录 ${records.size} 笔 · 全部数据仅存于本机", PAD.toFloat(), y, 30f, Color.parseColor("#8A8A8A"))
        text("MoneyTracker 智能账本", (W - PAD).toFloat(), y, 32f, Color.parseColor("#C15F3C"), bold = true, align = Paint.Align.RIGHT)

        return bmp
    }

    private fun fmt(v: Double): String = String.format("%,.2f", v)
}
