package com.rutger.fastify.playback

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.rutger.fastify.data.ArtistRef
import com.rutger.fastify.data.Track

const val TRACK_SCHEME = "fastify"

private const val EXTRA_ARTIST_IDS = "fastify.artistIds"
private const val EXTRA_ARTIST_NAMES = "fastify.artistNames"
private const val EXTRA_ALBUM_ID = "fastify.albumId"
private const val EXTRA_AUTOPLAY = "fastify.autoplay"

fun trackUri(id: String): Uri = Uri.Builder().scheme(TRACK_SCHEME).authority("track").appendPath(id).build()

/** `autoplay`: a recommendation appended after the queue ran out (shown under "Recommended"). */
fun Track.toMediaItem(autoplay: Boolean = false): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri(trackUri(id))
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artists)
            .setAlbumTitle(album)
            .setArtworkUri(imageUrl?.toUri())
            .setDurationMs(durationMs)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            // Ids for "Go to artist/album" from the player; extras survive the session IPC.
            .setExtras(
                Bundle().apply {
                    putStringArrayList(EXTRA_ARTIST_IDS, ArrayList(artistRefs.map { it.id }))
                    putStringArrayList(EXTRA_ARTIST_NAMES, ArrayList(artistRefs.map { it.name }))
                    albumId?.let { putString(EXTRA_ALBUM_ID, it) }
                    if (autoplay) putBoolean(EXTRA_AUTOPLAY, true)
                },
            )
            .build(),
    )
    .build()

fun MediaItem.toTrack(): Track {
    val extras = mediaMetadata.extras
    val ids = extras?.getStringArrayList(EXTRA_ARTIST_IDS).orEmpty()
    val names = extras?.getStringArrayList(EXTRA_ARTIST_NAMES).orEmpty()
    return Track(
        id = mediaId,
        title = mediaMetadata.title?.toString().orEmpty(),
        artists = mediaMetadata.artist?.toString().orEmpty(),
        album = mediaMetadata.albumTitle?.toString().orEmpty(),
        imageUrl = mediaMetadata.artworkUri?.toString(),
        durationMs = mediaMetadata.durationMs ?: 0,
        artistRefs = ids.zip(names) { id, name -> ArtistRef(id, name) },
        albumId = extras?.getString(EXTRA_ALBUM_ID),
    )
}

val MediaItem.isAutoplay: Boolean get() = mediaMetadata.extras?.getBoolean(EXTRA_AUTOPLAY) == true

/** Controllers strip the URI from media items; rebuild it from the media id. */
fun MediaItem.withPlayableUri(): MediaItem =
    if (localConfiguration != null) this else buildUpon().setUri(trackUri(mediaId)).build()
