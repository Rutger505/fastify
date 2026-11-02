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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    /** Queue indices in play order: shuffled order when shuffle is on. */
    val order: List<Int> = emptyList(),
    val index: Int = 0,
    /** Queue index where autoplay recommendations start, -1 when there are none. */
    val autoplayFrom: Int = -1,
    /** Queue indices added by hand. */
    val queued: Set<Int> = emptySet(),
    val error: String? = null,
    /** Name of the Spotify Connect device being controlled, null = playing on this phone. */
    val remoteDevice: String? = null,
)

/**
 * UI-side handle to the playback service through a MediaController. Main thread only.
 * While [remote] controls another Spotify Connect device, [state] and the commands go there.
 */
class PlayerConnection(private val context: Context, val remote: ConnectRemote) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = combine(_state, remote.ui) { local, r -> r ?: local }
        .stateIn(scope, SharingStarted.Eagerly, PlayerUiState())

    /** Local playback state, also while a remote device is controlled. */
    val localState: StateFlow<PlayerUiState> = _state
    private val remoteOn get() = remote.target.value != null

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
    /**
     * Play [tracks] from [startIndex] (-1 = from the top: a random track when shuffling).
     * [shuffle] null keeps the current shuffle mode, like Spotify. Shuffle is switched off and
     * back on around the swap so the service reshuffles with the start track first.
     */
    fun play(tracks: List<Track>, startIndex: Int = -1, shuffle: Boolean? = null, contextUri: String? = null) {
        if (remoteOn && tracks.isNotEmpty()) {
            val sh = shuffle ?: state.value.shuffle
            val start = if (startIndex >= 0) startIndex.coerceAtMost(tracks.lastIndex) else if (sh) tracks.indices.random() else 0
            return remote.play(tracks, start, shuffle, contextUri)
        }
        playLocal(tracks, startIndex, shuffle, contextUri)
    }

    private fun playLocal(tracks: List<Track>, startIndex: Int, shuffle: Boolean?, contextUri: String?) = withController {
        if (tracks.isEmpty()) return@withController
        val sh = shuffle ?: shuffleModeEnabled
        val start = if (startIndex >= 0) startIndex.coerceAtMost(tracks.lastIndex) else if (sh) tracks.indices.random() else 0
        setContext(contextUri)
        shuffleModeEnabled = false
        setMediaItems(tracks.map { it.toMediaItem() }, start, 0)
        shuffleModeEnabled = sh
        prepare()
        play()
    }

    fun togglePlay() = if (remoteOn) remote.togglePlay() else withController {
        if (playbackState == Player.STATE_IDLE) prepare()
        if (isPlaying) pause() else play()
    }

    fun next() = if (remoteOn) remote.next() else withController { seekToNext() }
    fun previous() = if (remoteOn) remote.previous() else withController { seekToPrevious() }

    /** Always the previous track (no restart-current-track rule), for the mini player swipe. */
    fun previousTrack() = if (remoteOn) remote.previous() else withController { seekToPreviousMediaItem() }
    fun seekTo(ms: Long) = if (remoteOn) remote.seekTo(ms) else withController { seekTo(ms) }
    fun playQueueIndex(index: Int) = if (remoteOn) remote.playQueueIndex(index) else withController { seekTo(index, 0); play() }
    fun toggleShuffle() = if (remoteOn) remote.toggleShuffle() else withController { shuffleModeEnabled = !shuffleModeEnabled }
    fun cycleRepeat() = if (remoteOn) remote.cycleRepeat() else withController {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun stopAndClear() {
        if (remoteOn) { remote.pause(); remote.detach(dismiss = true) }
        withController { setContext(null); stop(); clearMediaItems() }
    }

    /** Device picker "This phone": transfer playback from the active device here. */
    fun transferHere() {
        val active = remote.state.value?.activeDeviceId
        if (active == null) remote.detach(dismiss = true) else remote.transfer(from = active, to = null)
    }

    /**
     * Device picker: move playback to [deviceId], from whichever device plays now (this phone
     * included). Nothing loaded anywhere: just control that device.
     */
    fun transferTo(deviceId: String) {
        val active = remote.state.value?.activeDeviceId
        when {
            active == deviceId -> remote.attach(deviceId)
            active != null -> remote.transfer(from = active, to = deviceId)
            _state.value.current != null -> remote.transfer(from = null, to = deviceId)
            else -> remote.attach(deviceId)
        }
    }

    private fun MediaController.setContext(uri: String?) {
        sendCustomCommand(
            SessionCommand(CMD_SET_CONTEXT, Bundle.EMPTY),
            Bundle().apply { uri?.let { putString(ARG_CONTEXT_URI, it) } },
        )
    }

    // "Add to queue" items play after the current track and earlier queued ones, before the rest
    // of the playlist (like Spotify). In shuffle mode the service keeps them in that spot.
    fun addToQueue(track: Track) = if (remoteOn) remote.addToQueue(track) else withController {
        if (mediaItemCount == 0) {
            setMediaItem(track.toMediaItem())
            prepare()
            return@withController
        }
        var at = currentMediaItemIndex + 1
        while (at < mediaItemCount && getMediaItemAt(at).isQueued) at++
        addMediaItem(at, track.toMediaItem(queued = true))
    }

    fun removeFromQueue(index: Int) = if (remoteOn) remote.removeFromQueue(index) else withController {
        if (index in 0 until mediaItemCount && index != currentMediaItemIndex) removeMediaItem(index)
    }

    /** Live position, for lyrics sync (the state ticker only updates twice a second). */
    fun position(): Long = if (remoteOn) remote.position() else controller?.currentPosition ?: _state.value.positionMs

    private fun withController(block: MediaController.() -> Unit) {
        val c = controller
        if (c != null) block(c) else {
            pending = block
            connect()
        }
    }

    private fun refresh(full: Boolean) {
        val c = controller ?: return
        val before = _state.value
        // Playing on the phone (notification, Bluetooth, a transfer here) ends remote control.
        if (c.isPlaying && !before.isPlaying) remote.detach(dismiss = false)
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
            order = if (full) playOrder(c) else _state.value.order,
            index = c.currentMediaItemIndex,
            autoplayFrom = autoplayFrom,
            queued = if (full) (0 until c.mediaItemCount).filter { c.getMediaItemAt(it).isQueued }.toSet() else _state.value.queued,
            error = c.playerError?.let(::describe),
        )
        // E.g. another device took over and paused the phone: the remote may attach to it now.
        if (before.isPlaying != c.isPlaying || before.current?.id != _state.value.current?.id) remote.localChanged()
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(full = events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED))
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh(full = false)
    }

    private fun playOrder(c: MediaController): List<Int> {
        val t = c.currentTimeline
        if (t.isEmpty) return emptyList()
        val out = ArrayList<Int>(t.windowCount)
        var i = t.getFirstWindowIndex(c.shuffleModeEnabled)
        while (i != androidx.media3.common.C.INDEX_UNSET && out.size < t.windowCount) {
            out += i
            i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
        }
        return out
    }

    private fun describe(e: PlaybackException): String =
        e.errorCodeName + (e.cause?.message?.let { ": $it" } ?: "")
}
