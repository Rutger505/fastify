package com.rutger.fastify.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
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
    /** Queue index where autoplay recommendations start, -1 when there are none. */
    val autoplayFrom: Int = -1,
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

    /** `contextUri`: what the tracks are (spotify:playlist:..., album, artist, collection), seeds autoplay. */
    fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean = false, contextUri: String? = null) = withController {
        setContext(contextUri)
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

    fun stopAndClear() = withController { setContext(null); stop(); clearMediaItems() }

    private fun MediaController.setContext(uri: String?) {
        sendCustomCommand(
            SessionCommand(CMD_SET_CONTEXT, Bundle.EMPTY),
            Bundle().apply { uri?.let { putString(ARG_CONTEXT_URI, it) } },
        )
    }

    // "Add to queue" items play after the current track, in the order they were added
    // (like Spotify). The offset resets once playback moves to another track.
    private var queueAnchor: String? = null
    private var queueOffset = 0

    fun addToQueue(track: Track) = withController {
        if (mediaItemCount == 0) {
            setMediaItem(track.toMediaItem())
            prepare()
            return@withController
        }
        val current = currentMediaItem?.mediaId
        if (current != queueAnchor) {
            queueAnchor = current
            queueOffset = 0
        }
        val at = (currentMediaItemIndex + 1 + queueOffset).coerceAtMost(mediaItemCount)
        addMediaItem(at, track.toMediaItem())
        queueOffset++
    }

    fun removeFromQueue(index: Int) = withController {
        if (index in 0 until mediaItemCount && index != currentMediaItemIndex) removeMediaItem(index)
    }

    /** Live position, for lyrics sync (the state ticker only updates twice a second). */
    fun position(): Long = controller?.currentPosition ?: _state.value.positionMs

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
        val autoplayFrom = if (full) (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).isAutoplay } ?: -1
        else _state.value.autoplayFrom
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
            autoplayFrom = autoplayFrom,
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
