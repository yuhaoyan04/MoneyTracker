package com.mudasir.smartledger.util

import android.content.Context
import com.mudasir.smartledger.data.TransactionRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object FormatUtil {

    private val moneyFmt by lazy { java.text.DecimalFormat("#,##0.00") }

    fun money(amount: Double): String = "¥" + moneyFmt.format(amount)

    /** 带正负号：收入 +，支出 - */
    fun moneySigned(amount: Double, type: String): String {
        val sign = if (type == TransactionRecord.TYPE_INCOME) "+" else "-"
        return "$sign" + money(kotlin.math.abs(amount))
    }

    fun date(ts: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

    fun day(ts: Long): String =
        SimpleDateFormat("MM月dd日", Locale.getDefault()).format(Date(ts))

    fun monthLabel(year: Int, month0: Int): String =
        String.format(Locale.getDefault(), "%d年%d月", year, month0 + 1)

    /** 返回某年某月的起止时间戳（毫秒），end 为下个月起点。 */
    fun monthRange(year: Int, month0: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            set(year, month0, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val end = cal.timeInMillis
        return start to end
    }
}
