package com.rutger.fastify.data

import kotlinx.serialization.Serializable

// ---- App models (what the UI and caches use) ----

@Serializable
data class ArtistRef(val id: String, val name: String)

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artists: String,
    val album: String,
    val imageUrl: String?,
    val durationMs: Long,
    /** Artists with ids, for "Go to artist". Empty in snapshots written before this existed. */
    val artistRefs: List<ArtistRef> = emptyList(),
    val albumId: String? = null,
) {
    fun albumCollection(): Collection? = albumId?.let {
        Collection(CollectionKind.ALBUM, it, album, artists, imageUrl)
    }
}

@Serializable
enum class CollectionKind { LIKED, PLAYLIST, ALBUM }

@Serializable
data class Collection(
    val kind: CollectionKind,
    val id: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    /** Playlist owner's username, to know which playlists can be edited. */
    val owner: String? = null,
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

@Serializable
data class ArtistPageData(
    val id: String,
    val name: String,
    val imageUrl: String?,
    val biography: String?,
    val topTracks: List<Track>,
    val albums: List<Collection>,
    val singles: List<Collection>,
    val compilations: List<Collection>,
    val appearsOn: List<Collection>,
    /** Total streams per top track id; empty when Spotify didn't provide them. */
    val playcounts: Map<String, Long> = emptyMap(),
)

/** Album details the track list doesn't carry: who made it and when. */
@Serializable
data class AlbumHeader(
    val id: String,
    val artists: List<ArtistRef>,
    val year: Int?,
    val type: String,
)

@Serializable
data class LyricLineData(val startMs: Long, val words: String)

@Serializable
data class LyricsData(val synced: Boolean, val lines: List<LyricLineData>, val provider: String)

/** Cached lyrics lookup; [lyrics] null means Spotify has none for the track. */
@Serializable
data class LyricsResult(val lyrics: LyricsData?)

/** Cache-first value: [data] can be stale while [refreshing]. */
data class Loadable<T>(val data: T?, val refreshing: Boolean, val error: String? = null)
