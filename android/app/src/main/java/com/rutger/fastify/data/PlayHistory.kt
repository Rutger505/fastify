package com.rutger.fastify.data

import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Fastify's own "recently played": newest first, deduplicated, capped. */
class PlayHistory(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Track.serializer())
    private var items: MutableList<Track>? = null

    @Synchronized
    fun recent(): List<Track> = load().toList()

    @Synchronized
    fun record(track: Track) {
        val list = load()
        list.removeAll { it.id == track.id }
        list.add(0, track)
        while (list.size > MAX) list.removeAt(list.lastIndex)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, list))
        tmp.renameTo(file)
    }

    @Synchronized
    fun clear() {
        items = mutableListOf()
        file.delete()
    }

    private fun load(): MutableList<Track> = items ?: runCatching {
        json.decodeFromString(serializer, file.readText()).toMutableList()
    }.getOrDefault(mutableListOf()).also { items = it }

    private companion object {
        const val MAX = 50
    }
}
