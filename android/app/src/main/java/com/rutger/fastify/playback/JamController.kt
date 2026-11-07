package com.rutger.fastify.playback

import com.rutger.fastify.core.FastifyCore
import com.rutger.fastify.core.JamInfo
import com.rutger.fastify.core.JamPreview
import com.rutger.fastify.core.RemoteCommand
import com.rutger.fastify.diag.AppLog
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

data class JamUiState(
    val jam: JamInfo? = null,
    /** First read done (null jam = not in one, not "unknown"). */
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    /** Invite being looked at before joining (deep link, share, pasted). */
    val invite: String? = null,
    val preview: JamPreview? = null,
)

/**
 * Spotify Jam (social-connect) through the native session. Pushes come over the Connect dealer
 * ([FastifyCore.jamWatch]); every push re-reads the Jam.
 *
 * Guest listening on this phone: Spotify transfers the Jam here and relays every change as
 * Connect commands ([ConnectTarget] plays them). Our own skip/seek/queue/play must go to the
 * Jam instead ([redirect]), else the phone drifts away from it. Pause stays local, like Spotify.
 */
class JamController(private val scope: CoroutineScope, private val core: () -> FastifyCore, private val http: () -> OkHttpClient) {
    private val _state = MutableStateFlow(JamUiState())
    val state: StateFlow<JamUiState> = _state

    private val _sheet = MutableStateFlow(false)
    /** Jam sheet shown (opened from the player, a deep link or a share). */
    val sheet: StateFlow<Boolean> = _sheet

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** One-off messages (removed by the host, Jam ended) for a toast. */
    val notices: SharedFlow<String> = _notices

    private val generation = AtomicLong()
    private var watcher: Job? = null

    /** Session to redirect player commands to: guest playing the Jam on this phone. */
    val guestSessionId: String?
        get() = _state.value.jam?.takeIf { !it.isHost && it.listeningHere }?.sessionId

    fun openSheet() { _sheet.value = true; refresh() }
    fun closeSheet() { _sheet.value = false; _state.value = _state.value.copy(invite = null, preview = null, error = null) }

    /** Watch pushes for the app's lifetime: the service needs the Jam state in the background too. */
    @Synchronized
    fun startWatching() {
        if (watcher?.isActive == true) return
        watcher = scope.launch(Dispatchers.IO) {
            refresh()
            var version = 0uL
            var failures = 0
            while (isActive) {
                runCatching { core().jamWatch(version) }
                    .onSuccess { e ->
                        failures = 0
                        if (e.version != version) {
                            version = e.version
                            AppLog.i(TAG, "push ${e.reason}")
                            notice(e.reason)?.let { _notices.tryEmit(it) }
                            refresh()
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
    }

    private fun notice(reason: String): String? = when (reason) {
        "YOU_WERE_KICKED" -> "The host removed you from the Jam"
        "SESSION_DELETED" -> if (_state.value.jam?.isHost == false) "The host ended the Jam" else null
        else -> null
    }

    fun refresh() = run("refresh", quiet = true) { core().jamGet() }

    fun start() = run("start Jam") { core().jamStart() }

    /** Show [invite] (link, spotify:socialsession:, token) with who's in it, before joining. */
    fun openInvite(invite: String) {
        _sheet.value = true
        _state.value = _state.value.copy(invite = invite, preview = null, error = null)
        val request = generation.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            val r = runCatching { core().jamPreview(resolveInvite(invite)) }
            if (request != generation.get()) return@launch
            r.onSuccess { _state.value = _state.value.copy(preview = it) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    AppLog.w(TAG, "invite preview failed", e)
                    _state.value = _state.value.copy(error = describe(e))
                }
        }
    }

    fun join(invite: String, listenHere: Boolean) = run("join Jam") {
        core().jamJoin(resolveInvite(invite), listenHere)
    }

    fun leave() {
        val jam = _state.value.jam ?: return
        run("leave Jam") { core().jamLeave(jam.sessionId); null }
    }

    fun setQueueOnly(enabled: Boolean) {
        val jam = _state.value.jam ?: return
        run("change Jam permissions") {
            core().jamSetQueueOnly(jam.sessionId, enabled)
            core().jamGet()
        }
    }

    fun kick(memberId: String) {
        val jam = _state.value.jam ?: return
        run("remove Jam member") {
            core().jamKick(jam.sessionId, memberId)
            core().jamGet()
        }
    }

    /** Send [cmd] to the Jam when we're a guest playing it here. False: not in that mode. */
    fun redirect(cmd: RemoteCommand): Boolean {
        val id = guestSessionId ?: return false
        scope.launch(Dispatchers.IO) {
            runCatching { core().jamCommand(id, cmd) }.onFailure { e ->
                if (e is CancellationException) throw e
                AppLog.w(TAG, "command $cmd failed", e)
                _notices.tryEmit("Jam: ${describe(e)}")
            }
        }
        return true
    }

    /** spotify.link short links redirect (sometimes in script) to open.spotify.com/socialsession/<token>. */
    private fun resolveInvite(invite: String): String {
        val text = invite.trim()
        val short = Regex("""https?://spotify(\.app)?\.link/\S+""").find(text)?.value
        if (short == null) {
            // Shared text can wrap the link in a sentence.
            return Regex("""https://open\.spotify\.com/\S*socialsession/[A-Za-z0-9_-]+""").find(text)?.value
                ?: Regex("""spotify:socialsession:[A-Za-z0-9_-]+""").find(text)?.value
                ?: text
        }
        http().newCall(Request.Builder().url(short).get().build()).execute().use { response ->
            val landed = response.request.url.toString()
            TOKEN.find(landed)?.let { return "https://open.spotify.com/socialsession/${it.groupValues[1]}" }
            val body = response.body.string()
            val token = TOKEN.find(body)?.groupValues?.get(1)
                ?: error("${response.code}: link is not a Spotify Jam invite ($landed)")
            return "https://open.spotify.com/socialsession/$token"
        }
    }

    private fun run(label: String, quiet: Boolean = false, action: suspend () -> JamInfo?) {
        val request = generation.incrementAndGet()
        if (!quiet) _state.value = _state.value.copy(busy = true, error = null)
        scope.launch(Dispatchers.IO) {
            val result = runCatching { action() }
            if (request != generation.get()) return@launch
            result.onSuccess { jam ->
                // A finished join/start replaces the invite view with the Jam.
                _state.value = JamUiState(jam = jam, loaded = true)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                AppLog.w(TAG, "$label failed", e)
                _state.value = _state.value.copy(busy = false, loaded = true, error = if (quiet && !_sheet.value) null else "$label: ${describe(e)}")
            }
        }
    }

    private fun describe(e: Throwable) = "${e.javaClass.simpleName}: ${e.message}"

    private companion object {
        const val TAG = "Jam"
        val TOKEN = Regex("""socialsession(?:/|:|%2F|%3A)([A-Za-z0-9_-]{8,64})""", RegexOption.IGNORE_CASE)
    }
}
