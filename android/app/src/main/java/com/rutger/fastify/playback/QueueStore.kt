package com.rutger.fastify.playback

import com.rutger.fastify.data.Track
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SavedQueue(
    val tracks: List<Track>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
)

/** Last queue + position, restored paused on launch so play is instant. */
class QueueStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): SavedQueue? = runCatching {
        if (file.exists()) json.decodeFromString(SavedQueue.serializer(), file.readText()) else null
    }.getOrNull()?.takeIf { it.tracks.isNotEmpty() }

    @Synchronized
    fun save(queue: SavedQueue) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(SavedQueue.serializer(), queue))
        tmp.renameTo(file)
    }

    fun clear() {
        file.delete()
    }
}
