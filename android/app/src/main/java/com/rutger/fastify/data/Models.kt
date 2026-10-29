package com.rutger.fastify.data

import kotlinx.serialization.SerialName
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

// ---- Web API DTOs ----

@Serializable
data class ImageDto(val url: String, val width: Int? = null, val height: Int? = null)

@Serializable
data class ArtistDto(val id: String? = null, val name: String = "")

@Serializable
data class AlbumDto(
    val id: String? = null,
    val name: String = "",
    val images: List<ImageDto>? = null,
    val artists: List<ArtistDto> = emptyList(),
)

@Serializable
data class TrackDto(
    val id: String? = null,
    val name: String = "",
    val type: String = "track",
    val artists: List<ArtistDto> = emptyList(),
    val album: AlbumDto? = null,
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("is_local") val isLocal: Boolean = false,
)

@Serializable
data class Paging<T>(
    val items: List<T> = emptyList(),
    val next: String? = null,
    val total: Int = 0,
)

@Serializable
data class OwnerDto(@SerialName("display_name") val displayName: String? = null)

@Serializable
data class PlaylistDto(
    val id: String,
    val name: String = "",
    val images: List<ImageDto>? = null,
    val owner: OwnerDto? = null,
)

@Serializable
data class SavedTrackDto(val track: TrackDto? = null)

@Serializable
data class SavedAlbumDto(val album: AlbumDto? = null)

/** Playlist entries: `track` on the classic endpoint, `item` on the newer /items one. */
@Serializable
data class PlaylistItemDto(val track: TrackDto? = null, val item: TrackDto? = null)

@Serializable
data class PlayHistoryDto(val track: TrackDto? = null)

@Serializable
data class SearchDto(
    val tracks: Paging<TrackDto?>? = null,
    val albums: Paging<AlbumDto?>? = null,
    val playlists: Paging<PlaylistDto?>? = null,
)

// ---- Mapping ----

fun List<ImageDto>?.best(): String? = this?.maxByOrNull { it.width ?: 0 }?.url ?: this?.firstOrNull()?.url

fun TrackDto.toTrack(fallbackAlbum: AlbumDto? = null): Track? {
    val trackId = id ?: return null
    if (isLocal || type != "track") return null
    val a = album ?: fallbackAlbum
    return Track(
        id = trackId,
        title = name,
        artists = artists.joinToString(", ") { it.name },
        album = a?.name.orEmpty(),
        imageUrl = a?.images.best() ?: fallbackAlbum?.images.best(),
        durationMs = durationMs,
    )
}

fun AlbumDto.toCollection(): Collection? = id?.let {
    Collection(CollectionKind.ALBUM, it, name, artists.joinToString(", ") { a -> a.name }, images.best())
}

fun PlaylistDto.toCollection(): Collection =
    Collection(CollectionKind.PLAYLIST, id, name, owner?.displayName?.let { "By $it" }.orEmpty(), images.best())
