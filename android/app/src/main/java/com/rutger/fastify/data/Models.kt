package com.rutger.fastify.data

import kotlinx.serialization.Serializable

// ---- App models (what the UI and caches use) ----

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artists: String,
    val album: String,
    val imageUrl: String?,
    val durationMs: Long,
)

@Serializable
enum class CollectionKind { LIKED, PLAYLIST, ALBUM }

@Serializable
data class Collection(
    val kind: CollectionKind,
    val id: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
) {
    val cacheKey: String get() = "${kind.name.lowercase()}_$id"

    companion object {
        val Liked = Collection(CollectionKind.LIKED, "liked", "Liked Songs", "Your favourites", null)
    }
}

@Serializable
data class HomeData(
    val recent: List<Track> = emptyList(),
    val playlists: List<Collection> = emptyList(),
    val albums: List<Collection> = emptyList(),
)

@Serializable
data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val albums: List<Collection> = emptyList(),
    val playlists: List<Collection> = emptyList(),
)

/** Cache-first value: [data] can be stale while [refreshing]. */
data class Loadable<T>(val data: T?, val refreshing: Boolean, val error: String? = null)
