package com.rutger.fastify.data

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Recent search queries: newest first, deduplicated (case-insensitive), capped. */
class SearchHistory(private val file: File) {
    private val serializer = ListSerializer(String.serializer())
    private val _items = MutableStateFlow(
        runCatching { Json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList()),
    )
    val items: StateFlow<List<String>> = _items.asStateFlow()

    @Synchronized
    fun record(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        save(listOf(q) + _items.value.filterNot { it.equals(q, ignoreCase = true) }.take(MAX - 1))
    }

    @Synchronized
    fun remove(query: String) = save(_items.value - query)

    @Synchronized
    fun clear() = save(emptyList())

    private fun save(list: List<String>) {
        _items.value = list
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(Json.encodeToString(serializer, list))
            tmp.renameTo(file)
        }.onFailure { com.rutger.fastify.diag.AppLog.w("SearchHistory", "save failed", it) }
    }

    private companion object {
        const val MAX = 20
    }
}
