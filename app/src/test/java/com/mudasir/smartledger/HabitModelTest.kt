package com.mudasir.smartledger

import com.mudasir.smartledger.ml.HabitModel
import com.mudasir.smartledger.ml.HabitSample
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class HabitModelTest {
    private val day = 86_400_000L
    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 30, 18, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun at(daysAgo: Int, hour: Int, minute: Int) = Calendar.getInstance().apply {
        timeInMillis = now - daysAgo * day
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
    }.timeInMillis

    private fun sample(id: String, category: String, amount: Double, daysAgo: Int, hour: Int, minute: Int) =
        HabitSample(id, "EXPENSE", at(daysAgo, hour, minute), amount, category, "wallet", null)

    @Test fun distinguishesNearbyTimesByAmountAfterRepeatedFeedback() {
        val history = listOf(
            sample("l1", "lunch", 20.0, 2, 12, 0), sample("l2", "lunch", 21.0, 1, 12, 5),
            sample("w1", "water", 3.0, 2, 12, 30), sample("w2", "water", 3.0, 1, 12, 35),
            sample("m1", "metro", 4.0, 2, 14, 0), sample("m2", "metro", 4.0, 1, 14, 0)
        )
        assertEquals("lunch", HabitModel.suggest(history, sample("", "", 20.0, 0, 12, 10), now)?.category)
        assertEquals("water", HabitModel.suggest(history, sample("", "", 3.0, 0, 12, 30), now)?.category)
        assertEquals("metro", HabitModel.suggest(history, sample("", "", 4.0, 0, 14, 0), now)?.category)
        assertNull(HabitModel.suggest(history, sample("", "", 900.0, 0, 8, 0), now))
    }

    @Test fun editsReplaceTheSameTransactionAndAmbiguityAbstains() {
        val a = sample("same", "lunch", 20.0, 1, 12, 0)
        val corrected = a.copy(category = "shopping")
        val samples = HabitModel.upsert(HabitModel.upsert(emptyList(), a), corrected)
        assertEquals(1, samples.size)
        assertEquals("shopping", samples.single().category)
        assertTrue(HabitModel.updateReviewed(emptyList(), a, requireExisting = true).isEmpty())
        assertEquals("shopping", HabitModel.updateReviewed(listOf(a), corrected, requireExisting = true).single().category)
        val conflicting = listOf(sample("a", "a", 20.0, 1, 12, 0), sample("b", "a", 20.0, 2, 12, 0),
            sample("c", "b", 20.0, 1, 12, 0), sample("d", "b", 20.0, 2, 12, 0))
        assertNull(HabitModel.suggest(conflicting, sample("", "", 20.0, 0, 12, 0), now))
    }

    @Test fun recentHabitsCanOvertakeOldOnes() {
        val old = (1..8).map { sample("old$it", "old", 20.0, 360 + it, 12, 0) }
        val recent = (1..3).map { sample("new$it", "new", 20.0, it, 12, 0) }
        assertEquals("new", HabitModel.suggest(old + recent, sample("", "", 20.0, 0, 12, 0), now)?.category)
    }

    @Test fun typeAndInvalidAmountsDoNotBecomeEvidence() {
        val a = sample("a", "lunch", 20.0, 1, 12, 0)
        assertTrue(HabitModel.upsert(emptyList(), a.copy(amount = Double.NaN)).isEmpty())
        val query = sample("", "", 20.0, 0, 12, 0).copy(type = "INCOME")
        assertNull(HabitModel.suggest(listOf(a, a.copy(id = "b")), query, now))
    }

    @Test fun chronologicalReplayLearnsOnlyFromEarlierReviewedExamples() {
        var history = emptyList<HabitSample>()
        var attempted = 0
        var correct = 0
        for (daysAgo in 30 downTo 1) {
            val events = listOf(
                sample("l$daysAgo", "lunch", 20.0 + daysAgo % 3, daysAgo, 12, 5),
                sample("w$daysAgo", "water", 3.0, daysAgo, 12, 35),
                sample("m$daysAgo", "metro", 4.0, daysAgo, 14, 0)
            )
            for (event in events) {
                val prediction = HabitModel.suggest(history, event.copy(id = "", category = ""), event.timestamp)
                if (prediction != null) {
                    attempted++
                    if (prediction.category == event.category) correct++
                }
                history = HabitModel.upsert(history, event)
            }
        }
        assertTrue("Repeated routines should eventually receive suggestions", attempted >= 60)
        assertEquals("Controlled, non-overlapping routines should be classified correctly", attempted, correct)
    }
}
