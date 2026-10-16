package com.nonton.yuks

import android.content.Context
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.File
import java.io.OutputStreamWriter

/**
 * Download metadata persisted as JSON in filesDir/downloads.json. Reads and writes are serialized,
 * and writes go through AtomicFile so a crash midway can never replace a good store with a
 * half-written one.
 */
object DownloadStore {
    private const val FILE_NAME = "downloads.json"

    private val lock = Any()

    fun load(context: Context): MutableList<MutableMap<String, Any?>> {
        val file = File(context.filesDir, FILE_NAME)
        synchronized(lock) {
            if (!file.isFile) return mutableListOf()
            return try {
                AtomicFile(file).openRead().use { input ->
                    JsonReader(input.reader(Charsets.UTF_8)).use { reader ->
                        val entries = mutableListOf<MutableMap<String, Any?>>()
                        reader.beginArray()
                        while (reader.hasNext()) entries += readEntry(reader)
                        reader.endArray()
                        entries
                    }
                }
            } catch (_: Exception) {
                // Unreadable store: start clean, the videos on disk are adopted again on load.
                mutableListOf()
            }
        }
    }

    fun save(context: Context, entries: List<Map<String, Any?>>) {
        val file = File(context.filesDir, FILE_NAME)
        synchronized(lock) {
            val atomic = AtomicFile(file)
            var stream: java.io.FileOutputStream? = null
            try {
                stream = atomic.startWrite()
                val writer = JsonWriter(OutputStreamWriter(stream, Charsets.UTF_8))
                writer.beginArray()
                entries.forEach { entry ->
                    writer.beginObject()
                    entry.forEach { (key, value) ->
                        writer.name(key)
                        when (value) {
                            null -> writer.nullValue()
                            is Boolean -> writer.value(value)
                            is Int -> writer.value(value.toLong())
                            is Long -> writer.value(value)
                            is Float -> writer.value(value.toDouble())
                            is Double -> writer.value(value)
                            else -> writer.value(value.toString())
                        }
                    }
                    writer.endObject()
                }
                writer.endArray()
                writer.flush()
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                // The previous snapshot stays intact; the caller only logs this.
                if (stream != null) atomic.failWrite(stream)
                throw error
            }
        }
    }

    private fun readEntry(reader: JsonReader): MutableMap<String, Any?> {
        val entry = mutableMapOf<String, Any?>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (reader.peek()) {
                JsonToken.NULL -> {
                    reader.nextNull()
                    entry[name] = null
                }
                JsonToken.BOOLEAN -> entry[name] = reader.nextBoolean()
                // progress is the only fractional value in an entry.
                JsonToken.NUMBER ->
                    entry[name] = if (name == "progress") reader.nextDouble() else reader.nextLong()
                else -> entry[name] = reader.nextString()
            }
        }
        reader.endObject()
        return entry
    }
}
