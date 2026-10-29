package com.rutger.fastify.playback

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.rutger.fastify.data.Track

const val TRACK_SCHEME = "fastify"

fun trackUri(id: String): Uri = Uri.Builder().scheme(TRACK_SCHEME).authority("track").appendPath(id).build()

fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
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
            .build(),
    )
    .build()

fun MediaItem.toTrack(): Track = Track(
    id = mediaId,
    title = mediaMetadata.title?.toString().orEmpty(),
    artists = mediaMetadata.artist?.toString().orEmpty(),
    album = mediaMetadata.albumTitle?.toString().orEmpty(),
    imageUrl = mediaMetadata.artworkUri?.toString(),
    durationMs = mediaMetadata.durationMs ?: 0,
)

/** Controllers strip the URI from media items; rebuild it from the media id. */
fun MediaItem.withPlayableUri(): MediaItem =
    if (localConfiguration != null) this else buildUpon().setUri(trackUri(mediaId)).build()
