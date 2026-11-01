package com.rutger.fastify.playback

import androidx.media3.common.Player
import com.rutger.fastify.core.FastifyCore
import com.rutger.fastify.core.RemoteCommand
import com.rutger.fastify.core.RemoteState
import com.rutger.fastify.core.RemoteTrack
import com.rutger.fastify.data.Track
import com.rutger.fastify.data.toTrack
import com.rutger.fastify.diag.AppLog
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Spotify Connect remote: shows what another device on the account (e.g. the desktop app) plays
 * and controls it. While [target] is set, [PlayerConnection] routes the player UI here.
 *
 * Auto-attaches when another device is playing and the phone isn't (or the phone has nothing
 * loaded); detaches when nothing is active any more, or when local playback starts.
 */
class ConnectRemote(private val core: () -> FastifyCore, private val local: () -> PlayerUiState) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<RemoteState?>(null)
    /** Latest cluster state, null until the first one arrives. */
    val state: StateFlow<RemoteState?> = _state

    private val _target = MutableStateFlow<String?>(null)
    /** Device being controlled, null = this phone. */
    val target: StateFlow<String?> = _target

    private val _ui = MutableStateFlow<PlayerUiState?>(null)
    /** Player UI state for [target], null when controlling this phone. */
    val ui: StateFlow<PlayerUiState?> = _ui

    @Volatile private var error: String? = null
    private val meta = ConcurrentHashMap<String, Track>()
    private val hydrating = Mutex()
    private var watcher: Job? = null
    private var ticker: Job? = null
    /** Started activities; an old activity's onStop can come after the new one's onStart. */
    private var starts = 0
    /** Device + track the user switched away from; no auto-attach until that changes. */
    @Volatile private var dismissed: Pair<String, String?>? = null

    /** Watch the cluster while the app is visible. Pair every call with [stop]. */
    @Synchronized
    fun start() {
        starts++
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            var version = 0UL
            var failures = 0
            while (isActive) {
                runCatching { core().connectWatch(version) }
                    .onSuccess { s ->
                        failures = 0
                        if (s.version != version) {
                            version = s.version
                            s.error?.let { AppLog.w(TAG, "observer: ${it}") }
                            onState(s)
                        }
                    }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        AppLog.w(TAG, "watch failed", e)
                        delay(2_000L shl failures.coerceAtMost(4))
                        failures++
                    }
            }
        }
        ticker = scope.launch {
            while (isActive) {
                delay(500)
                val ui = _ui.value ?: continue
                if (ui.isPlaying) _ui.value = ui.copy(positionMs = position())
            }
        }
    }

    @Synchronized
    fun stop() {
        starts = (starts - 1).coerceAtLeast(0)
        if (starts > 0) return
        watcher?.cancel()
        ticker?.cancel()
        watcher = null
        ticker = null
    }

    /** Live position of the remote track, extrapolated from the last update. */
    fun position(): Long {
        val s = _state.value ?: return 0
        var pos = s.positionMs.toLong()
        if (s.isPlaying) pos += System.currentTimeMillis() - s.positionAtMs.toLong()
        val d = s.durationMs.toLong()
        return if (d > 0) pos.coerceIn(0, d) else pos.coerceAtLeast(0)
    }

    private suspend fun onState(s: RemoteState) {
        _state.value = s
        val active = s.activeDeviceId
        val t = _target.value
        when {
            // Playback moved (e.g. PC -> speaker from the desktop app): follow it.
            t != null && active != null && active != t -> _target.value = active
            t != null && active == null -> detach()
            t == null && active != null && s.track != null && dismissed != (active to s.track?.uri) &&
                local().let { l -> (s.isPlaying && !l.isPlaying) || l.current == null } -> {
                AppLog.i(TAG, "attach to ${s.devices.find { it.id == active }?.name ?: active}")
                dismissed = null
                _target.value = active
            }
        }
        render()
        hydrate(s)
    }

    /** Stop controlling the remote device (it keeps its state). */
    fun detach() {
        val t = _target.value ?: return
        dismissed = t to _state.value?.track?.uri
        _target.value = null
        _ui.value = null
    }

    fun attach(deviceId: String) {
        dismissed = null
        _target.value = deviceId
        render()
    }

    private fun trackOf(r: RemoteTrack): Track {
        val id = r.uri.removePrefix("spotify:track:")
        return meta[id] ?: Track(id, "", "", "", null, 0)
    }

    private fun render() {
        val s = _state.value
        val t = _target.value
        if (s == null || t == null) { _ui.value = null; return }
        val name = s.devices.find { it.id == t }?.name ?: "another device"
        val all = buildList {
            addAll(s.prevTracks)
            s.track?.let(::add)
            addAll(s.nextTracks)
        }
        val index = if (s.track != null) s.prevTracks.size else -1
        _ui.value = PlayerUiState(
            connected = true,
            current = s.track?.let(::trackOf),
            isPlaying = s.isPlaying,
            positionMs = position(),
            durationMs = s.durationMs.toLong(),
            shuffle = s.shuffle,
            repeatMode = when {
                s.repeatTrack -> Player.REPEAT_MODE_ONE
                s.repeatContext -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            },
            queue = all.map(::trackOf),
            order = all.indices.toList(),
            index = index.coerceAtLeast(0),
            autoplayFrom = all.indices.firstOrNull { it > index && all[it].provider == "autoplay" } ?: -1,
            queued = all.indices.filter { all[it].provider == "queue" }.toSet(),
            error = error ?: s.error,
            remoteDevice = name,
        )
    }

    /** Fetch titles/covers for the visible part of the queue. */
    private suspend fun hydrate(s: RemoteState) = hydrating.withLock {
        val ids = (listOfNotNull(s.track) + s.nextTracks.take(HYDRATE_AHEAD) + s.prevTracks.takeLast(HYDRATE_BEHIND))
            .map { it.uri.removePrefix("spotify:track:") }
            .filter { !meta.containsKey(it) }
            .distinct()
        if (ids.isEmpty()) return@withLock
        runCatching { core().tracks(ids) }
            .onSuccess { infos -> infos.forEach { meta[it.id] = it.toTrack() }; render() }
            .onFailure { AppLog.w(TAG, "track metadata", it) }
    }

    // ---- Commands (default: to [target]) ----

    private fun send(cmd: RemoteCommand, to: String? = _target.value) {
        val device = to ?: return
        scope.launch {
            runCatching { core().connectCommand(device, cmd) }
                .onSuccess { if (error != null) { error = null; render() } }
                .onFailure { e ->
                    AppLog.w(TAG, "command ${cmd.javaClass.simpleName} failed", e)
                    error = "Connect ${cmd.javaClass.simpleName}: ${e.message}"
                    render()
                }
        }
    }

    fun togglePlay() = send(if (_state.value?.isPlaying == true) RemoteCommand.Pause else RemoteCommand.Resume)
    fun next() = send(RemoteCommand.SkipNext)
    fun previous() = send(RemoteCommand.SkipPrev)
    fun seekTo(ms: Long) = send(RemoteCommand.SeekTo(ms.coerceAtLeast(0).toULong()))
    fun toggleShuffle() = send(RemoteCommand.SetShuffle(!(_state.value?.shuffle ?: false)))

    /** Off -> repeat context -> repeat track -> off, like Spotify. */
    fun cycleRepeat() {
        val s = _state.value ?: return
        send(when {
            s.repeatTrack -> RemoteCommand.SetRepeat(context = false, track = false)
            s.repeatContext -> RemoteCommand.SetRepeat(context = true, track = true)
            else -> RemoteCommand.SetRepeat(context = true, track = false)
        })
    }

    fun addToQueue(track: Track) = send(RemoteCommand.AddToQueue(track.id))

    /** [index] in [ui]'s queue (prev + current + next). */
    fun playQueueIndex(index: Int) {
        val s = _state.value ?: return
        val cur = s.prevTracks.size
        when {
            index > cur -> s.nextTracks.getOrNull(index - cur - 1)?.let { send(RemoteCommand.SkipTo(it)) }
            index < cur -> scope.launch {
                // No "jump back to" command: step back one track at a time.
                val device = _target.value ?: return@launch
                repeat(cur - index) {
                    runCatching { core().connectCommand(device, RemoteCommand.SkipPrev) }
                        .onFailure { AppLog.w(TAG, "skip back", it) }
                }
            }
        }
    }

    fun removeFromQueue(index: Int) {
        val s = _state.value ?: return
        val i = index - s.prevTracks.size - 1
        if (i !in s.nextTracks.indices) return
        send(RemoteCommand.SetQueue(s.nextTracks.filterIndexed { j, _ -> j != i }, s.prevTracks, s.queueRevision))
    }

    fun play(tracks: List<Track>, start: Int, shuffle: Boolean?, contextUri: String?) =
        send(RemoteCommand.Play(contextUri, tracks.map { it.id }, start.coerceAtLeast(0).toUInt(), 0UL, shuffle))

    fun pause() = send(RemoteCommand.Pause)

    fun setVolume(deviceId: String, volume: Int) = send(RemoteCommand.Volume(volume.coerceIn(0, 65535).toUInt()), deviceId)

    /**
     * Spotify Connect transfer: playback (queue, position, play/pause) moves from device [from] to
     * [to]; null = this phone. The server builds it from the source's state, so from this phone the
     * core PUTs the current state first. The receiving phone loads it in [ConnectTarget].
     */
    fun transfer(from: String?, to: String?) {
        scope.launch {
            runCatching {
                val me = core().connectDeviceId()
                core().connectTransfer(from ?: me, to ?: me)
            }
                .onSuccess {
                    error = null
                    if (to != null) attach(to) else detach()
                }
                .onFailure { e ->
                    AppLog.w(TAG, "transfer ${from ?: "phone"} -> ${to ?: "phone"} failed", e)
                    error = "Connect transfer: ${e.message}"
                    render()
                }
        }
    }

    companion object {
        private const val TAG = "Connect"
        private const val HYDRATE_AHEAD = 50
        private const val HYDRATE_BEHIND = 10
    }
}
