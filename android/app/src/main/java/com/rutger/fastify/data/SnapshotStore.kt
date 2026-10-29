package com.rutger.fastify.data

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Cache-first storage: one JSON file per screen, plus an in-memory copy.
 * Reads are a single small file, fast enough to do synchronously for the very first frame.
 */
class SnapshotStore(private val dir: File) {
    private val memory = ConcurrentHashMap<String, Any>()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        dir.mkdirs()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> read(key: String, serializer: KSerializer<T>): T? {
        memory[key]?.let { return it as T }
        val file = file(key)
        if (!file.exists()) return null
        return try {
            json.decodeFromString(serializer, file.readText()).also { memory[key] = it }
        } catch (e: Exception) {
            Log.w("FastifySnapshots", "dropping unreadable snapshot $key", e)
            file.delete()
            null
        }
    }

    fun <T : Any> write(key: String, serializer: KSerializer<T>, value: T) {
        memory[key] = value
        val file = file(key)
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        tmp.renameTo(file)
    }

    fun clear() {
        memory.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")
}
