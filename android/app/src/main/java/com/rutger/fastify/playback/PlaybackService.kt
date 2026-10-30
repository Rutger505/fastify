package com.rutger.fastify.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.rutger.fastify.container
import com.rutger.fastify.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()
        val c = container
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(SpotifyDataSource.Factory(c.streams)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // Start as soon as 250 ms is buffered; keep a minute ahead once playing.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 60_000, 250, 1_000)
                    .build(),
            )
            .build()
        player.addListener(PlayerEvents())

        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

        restoreQueue()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            saveQueue()
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveQueue()
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    /** Last queue comes back paused and prepared: cached tracks are ready before you tap play. */
    private fun restoreQueue() {
        val saved = container.queueStore.load() ?: return
        player.setMediaItems(
            saved.tracks.map { it.toMediaItem() },
            saved.index.coerceIn(0, saved.tracks.lastIndex),
            saved.positionMs,
        )
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeatMode
        player.prepare()
    }

    private fun saveQueue() {
        if (player.mediaItemCount == 0) return
        val queue = SavedQueue(
            tracks = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toTrack() },
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        )
        container.scope.launch(Dispatchers.IO) { container.queueStore.save(queue) }
    }

    private fun prefetchUpcoming() {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return
        val ids = mutableListOf<String>()
        var index = player.currentMediaItemIndex
        if (index !in 0 until timeline.windowCount) return
        for (i in 0 until PREFETCH_AHEAD) {
            index = timeline.getNextWindowIndex(index, player.repeatMode, player.shuffleModeEnabled)
            // End of the queue: stop (passing INDEX_UNSET back in crashes the timeline).
            if (index == C.INDEX_UNSET) break
            ids += player.getMediaItemAt(index).mediaId
        }
        container.prefetcher.prefetch(ids)
    }

    private inner class PlayerEvents : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            com.rutger.fastify.diag.AppLog.e("Player", "playback error ${error.errorCodeName} on ${player.currentMediaItem?.mediaId}", error)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.let { item ->
                container.scope.launch(Dispatchers.IO) { container.history.record(item.toTrack()) }
            }
            saveQueue()
            prefetchUpcoming()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveQueue() else prefetchUpcoming()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = saveQueue()

        override fun onRepeatModeChanged(repeatMode: Int) = saveQueue()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { it.withPlayableUri() }.toMutableList())

        // Bluetooth / lock-screen "play" after the service was killed.
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = container.queueStore.load()
                ?: return Futures.immediateFailedFuture(IllegalStateException("nothing to resume"))
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    saved.tracks.map { it.toMediaItem() },
                    saved.index,
                    saved.positionMs,
                ),
            )
        }
    }

    private companion object {
        const val PREFETCH_AHEAD = 3
    }
}
