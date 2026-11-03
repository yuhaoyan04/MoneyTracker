package com.mudasir.smartledger

import com.mudasir.smartledger.util.TrendMath
import com.mudasir.smartledger.util.TrendPoint
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class TrendMathTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private fun time(year: Int, month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance(zone).apply {
            clear(); set(year, month - 1, day, hour, 0, 0)
        }.timeInMillis

    @Test fun `rolling month includes today and excludes the previous day`() {
        val now = time(2026, 11, 3, 12)
        val points = listOf(TrendPoint(time(2026, 10, 5, 0), 10.0),
            TrendPoint(time(2026, 11, 3, 11), 20.0), TrendPoint(time(2026, 10, 4, 23), 100.0),
            TrendPoint(time(2026, 11, 3, 13), 100.0))
        val series = TrendMath.aggregate(points, now, 30, TrendMath.Group.DAY, zone)
        assertEquals(30, series.values.size)
        assertEquals(10.0, series.values.first(), 0.001)
        assertEquals(20.0, series.values.last(), 0.001)
        assertEquals(30.0, series.values.sum(), 0.001)
    }

    @Test fun `day is exactly the last 24 hours`() {
        val now = time(2026, 11, 3, 12)
        assertEquals(time(2026, 11, 2, 12), TrendMath.start(now, 1, zone))
    }

    @Test fun `calendar buckets handle year change and daylight saving`() {
        val newYork = TimeZone.getTimeZone("America/New_York")
        val now = Calendar.getInstance(newYork).apply { clear(); set(2026, Calendar.MARCH, 10, 12, 0) }.timeInMillis
        val series = TrendMath.aggregate(listOf(TrendPoint(now, 42.0)), now, 7, TrendMath.Group.DAY, newYork)
        assertEquals(7, series.values.size)
        assertEquals(42.0, series.values.last(), 0.001)
        val yearEnd = time(2027, 1, 2, 12)
        val monthly = TrendMath.aggregate(listOf(TrendPoint(yearEnd, 5.0)), yearEnd, 30, TrendMath.Group.MONTH, zone)
        assertEquals(2, monthly.values.size)
        assertEquals(5.0, monthly.values.last(), 0.001)
    }
}
