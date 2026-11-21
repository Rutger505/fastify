package com.rutger.fastify.playback

import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Controller -> service: the queue being set was started from this context ([ARG_CONTEXT_URI], may be absent). */
const val CMD_SET_CONTEXT = "fastify.setContext"
const val ARG_CONTEXT_URI = "uri"

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var skippingUnplayable = false
    /** Unplayable tracks skipped in a row: stops when the whole queue is (repeat all). */
    private var unplayableRun = 0

    /** What the current queue was started from (spotify:playlist:..., ...), seeds autoplay. */
    private var contextUri: String? = null
    private var autoplayJob: Job? = null
    /** Seed + queue size of the last autoplay request that returned nothing: don't repeat it. */
    private var autoplayExhausted: String? = null
    private var connect: ConnectTarget? = null
    private var playbackLog: PlaybackLog? = null
    private var focusGuard: FocusGuard? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var localPlayer: Player
    private var remotePlayer: ConnectRemotePlayer? = null
    /** Holding a [ConnectRemote.start]: the cluster is followed while the session is remote. */
    private var watchingRemote = false

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
        playbackLog = PlaybackLog(this, player).also { it.start() }
        focusGuard = FocusGuard(this, player) { c.core }.also { it.start() }

        localPlayer = JamAwarePlayer(player, c.jam, ::caller)
        session = MediaSession.Builder(this, localPlayer)
            .setCallback(SessionCallback())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

        restoreQueue()
        c.jam.startWatching()
        connect = ConnectTarget(this, player, { c.core }, ::playOrder, { contextUri }, ::setContext)
            .also { it.start() }
        remotePlayer = ConnectRemotePlayer(c.remote, ::caller)
        scope.launch { c.remote.target.collect(::route) }
    }

    /**
     * Controlling a Connect device: the session exposes it ([ConnectRemotePlayer]), so volume
     * keys, notification, lock screen and Bluetooth control that device, like Spotify. The app's
     * own controller sees it as remote and keeps its last local state ([PlayerConnection]).
     */
    private fun route(target: String?) {
        val s = session ?: return
        val remote = container.remote
        if (target != null && !watchingRemote) remote.start()
        if (target == null && watchingRemote) remote.stop()
        watchingRemote = target != null
        val want = if (target != null) remotePlayer ?: return else localPlayer
        if (s.player === want) return
        val name = target?.let { id -> remote.state.value?.devices?.find { it.id == id }?.name ?: id }
        AppLog.i("Session", "controls ${name ?: "this phone"}")
        s.player = want
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Controller of the session call running now: app UI, notification, Bluetooth, Assistant, ... */
    private fun caller(): String {
        val s = session ?: return "?"
        val c = s.controllerForCurrentRequest ?: return "app (no controller)"
        return if (s.isMediaNotificationController(c)) "notification" else c.packageName
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.i("Playback", "task removed (swiped away), playWhenReady ${player.playWhenReady}")
        // Controlling a playing Connect device: keep its notification and volume keys, like Spotify.
        val remotePlaying = session?.player === remotePlayer && remotePlayer?.playWhenReady == true
        if (!remotePlaying && (!player.playWhenReady || player.mediaItemCount == 0)) {
            saveQueue()
            stopSelf()
        }
    }

    override fun onDestroy() {
        playbackLog?.stop()
        playbackLog = null
        focusGuard?.stop()
        focusGuard = null
        connect?.stop()
        connect = null
        scope.cancel()
        if (watchingRemote) container.remote.stop()
        watchingRemote = false
        autoplayJob?.cancel()
        saveQueue()
        session?.release()
        session = null
        remotePlayer?.release()
        remotePlayer = null
        player.release()
        super.onDestroy()
    }

    /** Last queue comes back paused and prepared: cached tracks are ready before you tap play. */
    private fun restoreQueue() {
        val saved = container.queueStore.load() ?: return
        setContext(saved.contextUri)
        val autoplay = saved.autoplay.toSet()
        val queued = saved.queued.toSet()
        player.setMediaItems(
            saved.tracks.mapIndexed { i, t -> t.toMediaItem(autoplay = i in autoplay, queued = i in queued) },
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
            queued = items.indices.filter { items[it].isQueued },
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
     * Two queues, like Spotify: hand-queued tracks play right after the current one in the order
     * they were added, then the playlist (shuffled when shuffle is on), then recommendations.
     * [reshuffle]: new random order for everything after the current track (shuffle turned on).
     */
    private fun arrangeShuffle(reshuffle: Boolean) {
        val n = player.mediaItemCount
        if (!player.shuffleModeEnabled || n < 2) return
        val cur = player.currentMediaItemIndex.coerceIn(0, n - 1)
        val order = playOrder()
        val pos = order.indexOf(cur)
        val items = (0 until n).map { player.getMediaItemAt(it) }
        val head = if (reshuffle || pos < 0) listOf(cur) else order.subList(0, pos + 1)
        val headSet = head.toSet()
        val queued = (0 until n).filter { items[it].isQueued && it !in headSet }
        var rest = (if (reshuffle || pos < 0) (0 until n).toList() else order.subList(pos + 1, order.size))
            .filter { it !in headSet && !items[it].isQueued }
        if (reshuffle) rest = rest.filterNot { items[it].isAutoplay }.shuffled() + rest.filter { items[it].isAutoplay }.shuffled()
        val wanted = head + queued + rest
        if (wanted == order || wanted.size != n) return
        player.setShuffleOrder(AppendingShuffleOrder(ShuffleOrder.DefaultShuffleOrder(wanted.toIntArray(), Random.nextLong())))
    }

    /**
     * Spotify-style autoplay: when at most [AUTOPLAY_WHEN_LEFT] tracks are left (repeat off),
     * append recommendations for the queue's context, so playback just continues when the
     * playlist/album ends. Without a context (search, recently played) the last track seeds it.
     */
    private fun maybeAutoplay() {
        if (player.repeatMode != Player.REPEAT_MODE_OFF) return
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
        // Controllers read it from here, e.g. to show a playlist's shuffle state.
        session?.sessionExtras = Bundle().apply { uri?.let { putString(ARG_CONTEXT_URI, it) } }
    }

    private inner class PlayerEvents : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val id = player.currentMediaItem?.mediaId
            val unplayable = com.rutger.fastify.data.Unplayable.cause(error)
            if (unplayable == null || id == null) {
                AppLog.e("Player", "playback error ${error.errorCodeName} on $id", error)
                return
            }
            // No Ogg Vorbis file: retrying can't help. Skip it, the player shows why.
            container.unplayable.mark(id, unplayable.message)
            val title = player.currentMediaItem?.mediaMetadata?.title ?: id
            container.unplayable.reportSkip("Skipped $title: ${unplayable.message}")
            if (player.hasNextMediaItem() && ++unplayableRun < player.mediaItemCount) {
                AppLog.i("Player", "skip $id (source: unplayable, ${unplayable.message})")
                skippingUnplayable = true
                player.seekToNextMediaItem()
                player.prepare()
            } else {
                AppLog.i("Player", "stop at $id (unplayable, ${unplayable.message}), nothing after it")
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The skip notice stays for the track we skipped to, until the next change.
            if (skippingUnplayable) skippingUnplayable = false else container.unplayable.reportSkip(null)
            mediaItem?.let { item ->
                container.scope.launch(Dispatchers.IO) { container.history.record(item.toTrack()) }
            }
            saveQueue()
            prefetchUpcoming()
            maybeAutoplay()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                arrangeShuffle(reshuffle = false)
                maybeAutoplay()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) maybeAutoplay()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) unplayableRun = 0
            // Playing here (transfer, Connect command) ends remote control, also with the app closed.
            if (isPlaying && container.remote.target.value != null) {
                AppLog.i("Session", "phone plays: stop controlling the Connect device")
                container.remote.detach(dismiss = false)
            }
            if (!isPlaying) saveQueue() else prefetchUpcoming()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            // Every press reshuffles what's left; current track stays put.
            if (shuffleModeEnabled) arrangeShuffle(reshuffle = true)
            // Shuffle is per context: remembered for the next time it's played.
            contextUri?.let { container.shuffle.set(it, shuffleModeEnabled) }
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

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val key = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent::class.java)
            if (key?.action == android.view.KeyEvent.ACTION_DOWN) {
                AppLog.i("Playback", "media button ${android.view.KeyEvent.keyCodeToString(key.keyCode)} from ${controllerInfo.packageName}")
            }
            return super.onMediaButtonEvent(session, controllerInfo, intent)
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
