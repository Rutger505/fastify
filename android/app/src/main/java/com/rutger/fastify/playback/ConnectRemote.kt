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
    /** One at a time: cluster updates and local changes both decide [target]. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val _state = MutableStateFlow<RemoteState?>(null)
    /** Latest cluster state, null until the first one arrives. */
    val state: StateFlow<RemoteState?> = _state

    private val _target = MutableStateFlow<String?>(null)
    /** Device being controlled, null = this phone. */
    val target: StateFlow<String?> = _target

    private val _ui = MutableStateFlow<PlayerUiState?>(null)
    /** Player UI state for [target], null when controlling this phone. */
    val ui: StateFlow<PlayerUiState?> = _ui

    /** Device switch in progress: set on tap, cleared once the target is active (or it fails). */
    data class Switch(val to: String?, val name: String)

    private val _switching = MutableStateFlow<Switch?>(null)
    /** Transfer in progress, for instant feedback while the server and devices catch up. */
    val switching: StateFlow<Switch?> = _switching
    /** This phone's Connect device id, once known (a switch to the phone ends when it's active). */
    @Volatile private var meId: String? = null
    private var switchTimeout: Job? = null

    @Volatile private var error: String? = null
    private val meta = ConcurrentHashMap<String, Track>()
    private val hydrating = Mutex()
    private var watcher: Job? = null
    private var ticker: Job? = null
    /** Started activities; an old activity's onStop can come after the new one's onStart. */
    private var starts = 0
    /** Device + track the user switched away from; no auto-attach until that changes. */
    @Volatile private var dismissed: Pair<String, String?>? = null
    /**
     * Transfer in flight to this device (null = the phone) until the deadline: cluster states
     * that still name the old device are stale, don't follow or detach on them.
     */
    @Volatile private var expect: Pair<String?, Long>? = null

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
        decide()
        render()
        hydrate(s)
    }

    /** The phone's own playback changed (paused by a takeover, started, cleared). */
    fun localChanged() {
        scope.launch { decide(); render() }
    }

    /** Follow the active device, or attach to it when it plays and the phone doesn't. */
    private fun decide() {
        val s = _state.value ?: return
        val active = s.activeDeviceId
        _switching.value?.let { sw ->
            val done = if (sw.to != null) active == sw.to else (active != null && active == meId) || local().isPlaying
            if (done) endSwitch()
        }
        expect?.let { (device, until) ->
            if (active == device || System.currentTimeMillis() > until) expect = null else return
        }
        val t = _target.value
        when {
            // Playback moved (e.g. PC -> speaker from the desktop app): follow it.
            t != null && active != null && active != t -> _target.value = active
            t != null && active == null -> detach(dismiss = false)
            t == null && active != null && s.track != null && dismissed != (active to s.track?.uri) &&
                local().let { l -> (s.isPlaying && !l.isPlaying) || l.current == null } -> {
                AppLog.i(TAG, "attach to ${s.devices.find { it.id == active }?.name ?: active}")
                dismissed = null
                _target.value = active
            }
        }
    }

    /**
     * Stop controlling the remote device (it keeps its state). [dismiss]: the user chose the
     * phone, don't auto-attach to that device + track again.
     */
    fun detach(dismiss: Boolean) {
        val t = _target.value ?: return
        if (dismiss) dismissed = t to _state.value?.track?.uri
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
        val prev = s.prevTracks.filter(::playable)
        val all = buildList {
            addAll(prev)
            s.track?.let(::add)
            addAll(s.nextTracks.filter(::playable))
        }
        val index = if (s.track != null) prev.size else -1
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
            contextUri = s.contextUri,
        )
    }

    /** Delimiters, episodes etc. stay in the state (set_queue sends them back), not in the UI. */
    private fun playable(t: RemoteTrack) = t.uri.startsWith("spotify:track:")

    /** Fetch titles/covers for the visible part of the queue. */
    private suspend fun hydrate(s: RemoteState) = hydrating.withLock {
        val ids = (listOfNotNull(s.track) + s.nextTracks.filter(::playable).take(HYDRATE_AHEAD) +
            s.prevTracks.filter(::playable).takeLast(HYDRATE_BEHIND))
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

    /** [index] in [ui]'s queue (prev + current + next, playable only). */
    fun playQueueIndex(index: Int) {
        val s = _state.value ?: return
        val cur = s.prevTracks.count(::playable)
        when {
            index > cur -> s.nextTracks.filter(::playable).getOrNull(index - cur - 1)?.let { send(RemoteCommand.SkipTo(it)) }
            index < cur -> scope.launch {
                // No "jump back to" command: step back one track at a time. Seek to 0 first,
                // else the first skip_prev only restarts the current track (past 3 s).
                val device = _target.value ?: return@launch
                runCatching {
                    core().connectCommand(device, RemoteCommand.SeekTo(0UL))
                    repeat(cur - index) { core().connectCommand(device, RemoteCommand.SkipPrev) }
                }.onFailure { e ->
                    AppLog.w(TAG, "skip back", e)
                    error = "Connect skip back: ${e.message}"
                    render()
                }
            }
        }
    }

    fun removeFromQueue(index: Int) {
        val s = _state.value ?: return
        val item = s.nextTracks.filter(::playable).getOrNull(index - s.prevTracks.count(::playable) - 1) ?: return
        val i = s.nextTracks.indexOf(item)
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
        // Synchronously: the UI shows the switch before the (slow) POST and cluster update.
        val name = to?.let { id -> _state.value?.devices?.find { it.id == id }?.name ?: "another device" } ?: "this phone"
        _switching.value = Switch(to, name)
        val started = System.currentTimeMillis()
        scope.launch {
            switchTimeout?.cancel()
            switchTimeout = scope.launch {
                delay(TRANSFER_SETTLE_MS)
                if (_switching.value?.to == to) {
                    AppLog.w(TAG, "transfer to $name: not active after ${TRANSFER_SETTLE_MS} ms")
                    endSwitch()
                }
            }
            runCatching {
                val me = core().connectDeviceId().also { meId = it }
                core().connectTransfer(from ?: me, to ?: me)
            }
                .onSuccess {
                    AppLog.i(TAG, "transfer -> $name accepted in ${System.currentTimeMillis() - started} ms")
                    error = null
                    expect = to to System.currentTimeMillis() + TRANSFER_SETTLE_MS
                    if (to != null) attach(to) else detach(dismiss = false)
                    decide()
                }
                .onFailure { e ->
                    AppLog.w(TAG, "transfer ${from ?: "phone"} -> ${to ?: "phone"} failed", e)
                    endSwitch()
                    error = "Connect transfer: ${e.message}"
                    render()
                }
        }
    }

    private fun endSwitch() {
        val sw = _switching.value ?: return
        AppLog.i(TAG, "switch to ${sw.name} done")
        switchTimeout?.cancel()
        _switching.value = null
    }

    companion object {
        private const val TAG = "Connect"
        private const val HYDRATE_AHEAD = 50
        private const val HYDRATE_BEHIND = 10
        private const val TRANSFER_SETTLE_MS = 8_000L
    }
}
