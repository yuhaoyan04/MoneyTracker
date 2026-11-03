package com.mudasir.smartledger.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class TrendPoint(val timestamp: Long, val amount: Double)
data class TrendSeries(val labels: List<String>, val values: List<Double>)

/** Local-calendar buckets for rolling periods; all windows include the current day. */
object TrendMath {
    enum class Group { DAY, WEEK, MONTH, QUARTER }

    fun start(now: Long, days: Int, zone: TimeZone = TimeZone.getDefault()): Long {
        require(days > 0)
        if (days == 1) return now - 24L * 60 * 60 * 1000
        return Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -(days - 1))
        }.timeInMillis
    }

    fun aggregate(points: List<TrendPoint>, now: Long, days: Int, group: Group,
                  zone: TimeZone = TimeZone.getDefault()): TrendSeries {
        require(days > 1)
        val start = start(now, days, zone)
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone }
        val label = SimpleDateFormat("M/d", Locale.getDefault()).apply { timeZone = zone }
        val dates = (0 until days).map { offset ->
            Calendar.getInstance(zone).apply { timeInMillis = start; add(Calendar.DAY_OF_YEAR, offset) }
        }
        val indices = dates.mapIndexed { i, date -> dateKey.format(date.time) to i }.toMap()
        val daily = DoubleArray(days)
        points.forEach { p ->
            if (p.timestamp in start..now && p.amount.isFinite() && p.amount > 0) {
                indices[dateKey.format(java.util.Date(p.timestamp))]?.let { daily[it] += p.amount }
            }
        }
        val labels = mutableListOf<String>()
        val values = mutableListOf<Double>()
        var lastKey = ""
        dates.forEachIndexed { i, date ->
            val year = date.get(Calendar.YEAR)
            val month = date.get(Calendar.MONTH)
            val key = when (group) {
                Group.DAY -> "$i"
                Group.WEEK -> "${i / 7}"
                Group.MONTH -> "$year-$month"
                Group.QUARTER -> "$year-${month / 3}"
            }
            if (key != lastKey) {
                labels.add(when (group) {
                    Group.DAY, Group.WEEK -> label.format(date.time)
                    Group.MONTH -> "${year}年${month + 1}月"
                    Group.QUARTER -> "${year}年Q${month / 3 + 1}"
                })
                values.add(0.0)
                lastKey = key
            }
            values[values.lastIndex] += daily[i]
        }
        return TrendSeries(labels, values)
    }
}
