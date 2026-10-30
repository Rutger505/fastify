package com.rutger.fastify.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.rutger.fastify.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val connected: Boolean = false,
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<Track> = emptyList(),
    val index: Int = 0,
    val error: String? = null,
)

/** UI-side handle to the playback service through a MediaController. Main thread only. */
class PlayerConnection(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ticker: Job? = null
    private var pending: (MediaController.() -> Unit)? = null

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            refresh(full = true)
            pending?.invoke(c)
            pending = null
        }, ContextCompat.getMainExecutor(context))
        ticker = scope.launch {
            while (isActive) {
                controller?.let { c ->
                    if (c.isPlaying) _state.value = _state.value.copy(positionMs = c.currentPosition)
                }
                delay(500)
            }
        }
    }

    fun disconnect() {
        ticker?.cancel()
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        _state.value = _state.value.copy(connected = false)
    }

    fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean = false) = withController {
        shuffleModeEnabled = shuffle
        setMediaItems(tracks.map { it.toMediaItem() }, if (shuffle) 0 else startIndex, 0)
        if (shuffle) seekTo(currentTimeline.getFirstWindowIndex(true).coerceAtLeast(0), 0)
        prepare()
        play()
    }

    fun togglePlay() = withController {
        if (playbackState == Player.STATE_IDLE) prepare()
        if (isPlaying) pause() else play()
    }

    fun next() = withController { seekToNext() }
    fun previous() = withController { seekToPrevious() }

    /** Always the previous track (no restart-current-track rule), for the mini player swipe. */
    fun previousTrack() = withController { seekToPreviousMediaItem() }
    fun seekTo(ms: Long) = withController { seekTo(ms) }
    fun playQueueIndex(index: Int) = withController { seekTo(index, 0); play() }
    fun toggleShuffle() = withController { shuffleModeEnabled = !shuffleModeEnabled }
    fun cycleRepeat() = withController {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun stopAndClear() = withController { stop(); clearMediaItems() }

    private fun withController(block: MediaController.() -> Unit) {
        val c = controller
        if (c != null) block(c) else {
            pending = block
            connect()
        }
    }

    private fun refresh(full: Boolean) {
        val c = controller ?: return
        val queue = if (full) (0 until c.mediaItemCount).map { c.getMediaItemAt(it).toTrack() } else _state.value.queue
        _state.value = PlayerUiState(
            connected = true,
            current = c.currentMediaItem?.toTrack(),
            isPlaying = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition,
            durationMs = c.duration.takeIf { it > 0 } ?: (c.currentMediaItem?.mediaMetadata?.durationMs ?: 0),
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            queue = queue,
            index = c.currentMediaItemIndex,
            error = c.playerError?.let(::describe),
        )
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(full = events.contains(Player.EVENT_TIMELINE_CHANGED))
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh(full = false)
    }

    private fun describe(e: PlaybackException): String =
        e.errorCodeName + (e.cause?.message?.let { ": $it" } ?: "")
}
