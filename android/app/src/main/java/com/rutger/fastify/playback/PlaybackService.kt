package com.rutger.fastify.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
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
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.rutger.fastify.container
import com.rutger.fastify.data.toTrack
import com.rutger.fastify.diag.AppLog
import com.rutger.fastify.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Controller -> service: the queue being set was started from this context ([ARG_CONTEXT_URI], may be absent). */
const val CMD_SET_CONTEXT = "fastify.setContext"
const val ARG_CONTEXT_URI = "uri"

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer

    /** What the current queue was started from (spotify:playlist:..., ...), seeds autoplay. */
    private var contextUri: String? = null
    private var autoplayJob: Job? = null
    /** Seed + queue size of the last autoplay request that returned nothing: don't repeat it. */
    private var autoplayExhausted: String? = null

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
        player.setShuffleOrder(AppendingShuffleOrder(0))
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
        autoplayJob?.cancel()
        saveQueue()
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    /** Last queue comes back paused and prepared: cached tracks are ready before you tap play. */
    private fun restoreQueue() {
        val saved = container.queueStore.load() ?: return
        contextUri = saved.contextUri
        val autoplay = saved.autoplay.toSet()
        player.setMediaItems(
            saved.tracks.mapIndexed { i, t -> t.toMediaItem(autoplay = i in autoplay) },
            saved.index.coerceIn(0, saved.tracks.lastIndex),
            saved.positionMs,
        )
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeatMode
        player.prepare()
    }

    private fun saveQueue() {
        if (player.mediaItemCount == 0) return
        val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val queue = SavedQueue(
            tracks = items.map { it.toTrack() },
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            contextUri = contextUri,
            autoplay = items.indices.filter { items[it].isAutoplay },
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

    /** Queue indices in play order (shuffle order when shuffling). */
    private fun playOrder(): List<Int> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val order = ArrayList<Int>(timeline.windowCount)
        var i = timeline.getFirstWindowIndex(player.shuffleModeEnabled)
        while (i != C.INDEX_UNSET) {
            order += i
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
        }
        return order
    }

    /**
     * Spotify-style autoplay: when at most [AUTOPLAY_WHEN_LEFT] tracks are left (repeat off),
     * append recommendations for the queue's context, so playback just continues when the
     * playlist/album ends. Without a context (search, recently played) the last track seeds it.
     */
    private fun maybeAutoplay() {
        if (!container.settings.autoplay.value || player.repeatMode != Player.REPEAT_MODE_OFF) return
        if (player.mediaItemCount == 0 || autoplayJob?.isActive == true) return
        val indices = playOrder()
        val pos = indices.indexOf(player.currentMediaItemIndex).takeIf { it >= 0 } ?: return
        if (indices.size - 1 - pos > AUTOPLAY_WHEN_LEFT) return
        val order = indices.map { player.getMediaItemAt(it).mediaId }

        val seed = contextUri ?: "spotify:track:${order.last()}"
        val key = "$seed|${order.size}"
        if (key == autoplayExhausted) return
        val count = player.mediaItemCount
        val context = contextUri
        autoplayJob = container.scope.launch(Dispatchers.Main) {
            try {
                val tracks = withContext(Dispatchers.IO) {
                    container.core.autoplay(seed, order).map { it.toTrack() }
                }
                // The queue changed meanwhile (other playlist started, track removed): drop it.
                if (player.mediaItemCount != count || contextUri != context) return@launch
                AppLog.i("Autoplay", "$seed: +${tracks.size} tracks")
                if (tracks.isEmpty()) {
                    autoplayExhausted = key
                    return@launch
                }
                val ended = player.playbackState == Player.STATE_ENDED
                player.addMediaItems(tracks.map { it.toMediaItem(autoplay = true) })
                if (ended) {
                    player.seekTo(count, 0)
                    player.play()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Autoplay", "$seed failed", e)
            }
        }
    }

    private fun setContext(uri: String?) {
        contextUri = uri
        autoplayJob?.cancel()
        autoplayExhausted = null
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
            maybeAutoplay()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) maybeAutoplay()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) maybeAutoplay()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveQueue() else prefetchUpcoming()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            saveQueue()
            maybeAutoplay()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            saveQueue()
            maybeAutoplay()
        }
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(CMD_SET_CONTEXT, Bundle.EMPTY))
                        .build(),
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_SET_CONTEXT) {
                setContext(args.getString(ARG_CONTEXT_URI))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

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
        const val AUTOPLAY_WHEN_LEFT = 1
    }
}
