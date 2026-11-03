package com.mudasir.smartledger.ml

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mudasir.smartledger.data.TransactionRecord
import java.io.File

/** Private, on-device training store. The legacy model is retained as a fallback. */
object HabitLearning {
    private val lock = Any()
    private val gson = Gson()
    private val sampleType = object : TypeToken<List<HabitSample>>() {}.type

    private fun storage(context: Context) = AtomicFile(File(context.applicationContext.filesDir, "habit_samples_v1.json"))

    private fun read(context: Context): List<HabitSample> {
        val file = storage(context)
        val stream = try { file.openRead() } catch (_: java.io.FileNotFoundException) { return emptyList() }
        return stream.bufferedReader(Charsets.UTF_8).use { reader ->
            gson.fromJson<List<HabitSample>>(reader, sampleType) ?: error("Invalid habit data")
        }
    }

    private fun write(context: Context, samples: List<HabitSample>) {
        val file = storage(context)
        val stream = file.startWrite()
        try {
            stream.write(gson.toJson(samples).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    fun recordReviewed(context: Context, record: TransactionRecord, requireExisting: Boolean = false) = synchronized(lock) {
        if (record.status != TransactionRecord.STATUS_CONFIRMED || record.isDeleted) return@synchronized
        runCatching {
            val sample = HabitSample(record.stableId, record.type, record.timestamp, record.amount,
                record.categoryName, record.channelName, record.merchant)
            val previous = read(context)
            val updated = HabitModel.updateReviewed(previous, sample, requireExisting)
            if (updated !== previous) write(context, updated)
        }.onFailure { Log.w("HabitLearning", "Unable to save reviewed sample", it) }
    }

    fun remove(context: Context, stableId: String) = synchronized(lock) {
        runCatching { write(context, read(context).filterNot { it.id == stableId }) }
            .onFailure { Log.w("HabitLearning", "Unable to remove sample", it) }
    }

    fun removeType(context: Context, type: String) = synchronized(lock) {
        runCatching { write(context, read(context).filterNot { it.type == type }) }
            .onFailure { Log.w("HabitLearning", "Unable to remove samples", it) }
    }

    fun sampleCount(context: Context): Int = synchronized(lock) {
        runCatching { read(context).size }.getOrElse { 0 }
    }

    fun suggest(context: Context, type: String, timestamp: Long, amount: Double,
                channel: String, merchant: String?): HabitSuggestion? = synchronized(lock) {
        runCatching {
            val query = HabitSample("", type, timestamp, amount, "", channel, merchant)
            HabitModel.suggest(read(context), query, System.currentTimeMillis())
        }.getOrNull()
    }
}
