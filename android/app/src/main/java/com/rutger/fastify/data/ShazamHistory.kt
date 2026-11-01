package com.rutger.fastify.data

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** What Shazam heard, plus the Spotify tracks it maps to (best first; may be empty). */
@Serializable
data class ShazamResult(
    val title: String,
    val artist: String,
    val coverUrl: String?,
    val tracks: List<Track>,
    val at: Long = System.currentTimeMillis(),
)

/** Recognized songs, newest first, capped. */
class ShazamHistory(private val file: File, private val seenFile: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ShazamResult.serializer())
    private val _items = MutableStateFlow(runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList()))
    val items: StateFlow<List<ShazamResult>> = _items.asStateFlow()

    /** [ShazamResult.at] of the newest result the Herkennen tab has shown (drives the tab badge). */
    private val _seenAt = MutableStateFlow(
        runCatching { seenFile.readText().trim().toLong() }.getOrElse { _items.value.firstOrNull()?.at ?: 0L },
    )
    val seenAt: StateFlow<Long> = _seenAt.asStateFlow()

    @Synchronized
    fun markSeen() {
        val newest = _items.value.firstOrNull()?.at ?: return
        if (newest <= _seenAt.value) return
        _seenAt.value = newest
        runCatching { seenFile.writeText(newest.toString()) }
            .onFailure { com.rutger.fastify.diag.AppLog.w("ShazamHistory", "save seen failed", it) }
    }

    @Synchronized
    fun record(r: ShazamResult) = save(listOf(r) + _items.value.filterNot { it.title == r.title && it.artist == r.artist }.take(MAX - 1))

    @Synchronized
    fun remove(r: ShazamResult) = save(_items.value - r)

    private fun save(list: List<ShazamResult>) {
        _items.value = list
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, list))
            tmp.renameTo(file)
        }.onFailure { com.rutger.fastify.diag.AppLog.w("ShazamHistory", "save failed", it) }
    }

    private companion object {
        const val MAX = 50
    }
}
