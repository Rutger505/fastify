package com.rutger.fastify.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rutger.fastify.R
import com.rutger.fastify.data.LibraryRepository
import com.rutger.fastify.data.ShazamHistory
import com.rutger.fastify.data.ShazamResult
import com.rutger.fastify.diag.AppLog
import com.rutger.fastify.container
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ShazamState {
    data object Idle : ShazamState
    /** [heardMs] of audio recorded so far; [asking] = a request to Shazam is in flight. */
    data class Listening(val heardMs: Long, val asking: Boolean) : ShazamState
    data class Found(val result: ShazamResult) : ShazamState
    data object NoMatch : ShazamState
    data class Failed(val message: String) : ShazamState
}

/**
 * Records 16 kHz mono from the mic and asks Shazam after 4, 8 and 12 s of audio, stopping at the
 * first match (Shazam usually knows a song within a few seconds).
 *
 * Activity-scoped (created in FastifyNavHost) so the tab and the stealth loader share one run.
 * Stealth = started by double-tapping the bottom tab: a full-screen fake loading screen covers the
 * app, the result comes as vibration (1 short = found, 2 = no match/failed), then Home shows.
 */
class ShazamViewModel(
    private val app: Context,
    private val repo: LibraryRepository,
    private val history: ShazamHistory,
) : ViewModel() {
    private val _state = MutableStateFlow<ShazamState>(ShazamState.Idle)
    val state: StateFlow<ShazamState> = _state
    private val _stealth = MutableStateFlow(false)
    val stealth: StateFlow<Boolean> = _stealth
    /** Emits when a stealth run finished (not when cancelled); call [stealthFinished] after leaving. */
    private val _stealthDone = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stealthDone: SharedFlow<Unit> = _stealthDone
    private var job: Job? = null

    /** A stealth start during a normal run turns that run stealth. */
    fun start(stealth: Boolean = false) {
        if (stealth) _stealth.value = true
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.value = ShazamState.Listening(0, false)
            _state.value = try {
                listen()?.let { history.record(it); ShazamState.Found(it) } ?: ShazamState.NoMatch
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.w("Shazam", "recognize failed", e)
                ShazamState.Failed("${e.javaClass.simpleName}: ${e.message}")
            }
            if (_stealth.value) {
                vibrate(found = _state.value is ShazamState.Found)
                // The loader stays until the app has switched to Home (stealthFinished), so the
                // Herkennen tab never flashes and doesn't mark the new result as seen.
                _stealthDone.tryEmit(Unit)
            }
        }
    }

    fun stealthFinished() {
        _stealth.value = false
    }

    fun stop() {
        job?.cancel()
        _stealth.value = false
        _state.value = ShazamState.Idle
    }

    private fun vibrate(found: Boolean) {
        val v = app.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        v.vibrate(
            if (found) VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
            else VibrationEffect.createWaveform(longArrayOf(0, 80, 120, 80), -1),
        )
    }

    @SuppressLint("MissingPermission") // checked by the screen before start()
    private suspend fun listen(): ShazamResult? = withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed (state ${rec.state}, minBuffer $min)" }
        val pcm = ShortArray(RATE * MAX_S)
        var n = 0
        try {
            rec.startRecording()
            for (checkpoint in CHECKPOINTS_S) {
                while (n < checkpoint * RATE && isActive) {
                    val r = rec.read(pcm, n, minOf(RATE / 10, checkpoint * RATE - n))
                    check(r >= 0) { "AudioRecord.read error $r" }
                    n += r
                    _state.value = ShazamState.Listening(n * 1000L / RATE, false)
                }
                _state.value = ShazamState.Listening(n * 1000L / RATE, true)
                // Keep recording while Shazam answers isn't worth the complexity: answers take ~1 s.
                repo.shazam(pcm.copyOf(n))?.let { return@withContext it }
            }
            null
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    private companion object {
        const val RATE = 16_000
        const val MAX_S = 12
        val CHECKPOINTS_S = intArrayOf(4, 8, 12)
    }
}

@Composable
fun ShazamScreen(vm: ShazamViewModel) {
    val ctx = LocalContext.current
    val c = ctx.container
    val state by vm.state.collectAsStateWithLifecycle()
    val found by c.shazamHistory.items.collectAsStateWithLifecycle()
    val downloaded by c.downloads.trackIds.collectAsStateWithLifecycle()
    val playerState by c.player.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf<TrackMenu?>(null) }
    var denied by remember { mutableStateOf(false) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        denied = !ok
        if (ok) vm.start()
    }
    val listening = state is ShazamState.Listening
    val onButton = {
        when {
            listening -> vm.stop()
            hasMic(ctx) -> vm.start()
            else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                FilledTonalButton(onClick = onButton, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(if (listening) Icons.Default.Stop else Icons.Default.GraphicEq, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (listening) "Stop" else "Herkennen")
                }
                when (val s = state) {
                    is ShazamState.Listening -> {
                        Spacer(Modifier.height(12.dp))
                        Text(if (s.asking) "Asking Shazam… (${s.heardMs / 1000} s heard)" else "Listening… ${s.heardMs / 1000} s",
                            style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { s.heardMs / 12_000f }, modifier = Modifier.fillMaxWidth())
                    }
                    ShazamState.NoMatch -> {
                        Spacer(Modifier.height(12.dp))
                        Text("No match after 12 s. Get closer to the speaker and try again.")
                    }
                    is ShazamState.Failed -> {
                        Spacer(Modifier.height(12.dp))
                        ErrorCard("Shazam failed", s.message, onRetry = onButton)
                    }
                    ShazamState.Idle, is ShazamState.Found -> {}
                }
                if (denied) Text("Microphone permission denied", color = MaterialTheme.colorScheme.error)
            }
        }
        (state as? ShazamState.Found)?.result?.let { r ->
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(r.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(r.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (r.tracks.isEmpty()) Text("Not found on Spotify", color = MaterialTheme.colorScheme.error)
                }
            }
            itemsIndexed(r.tracks, key = { _, t -> "now" + t.id }) { i, t ->
                TrackRow(t, playing = playerState.current?.id == t.id, onClick = { c.player.play(r.tracks, i) },
                    downloaded = t.id in downloaded, onMore = { menu = TrackMenu(t) }, onSwipeQueue = { c.player.addToQueue(t) })
            }
        }
        val past = found.filterNot { (state as? ShazamState.Found)?.result == it }
        if (found.isEmpty()) {
            item {
                Text("Nothing recognized yet", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Grouped per night out: a song at 02:00 belongs to the evening before.
        past.groupBy { nightOf(it.at) }.forEach { (night, results) ->
            item(key = "night$night") { SectionHeader(nightLabel(night)) }
            items(results, key = { "h" + it.at }) { r ->
                val t = r.tracks.firstOrNull()
                val time = TIME.format(Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault()))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (t != null) {
                            TrackRow(t, playing = playerState.current?.id == t.id, onClick = { c.player.play(listOf(t), 0) },
                                downloaded = t.id in downloaded, onMore = { menu = TrackMenu(t) }, onSwipeQueue = { c.player.addToQueue(t) },
                                trailing = time)
                        } else {
                            Column(Modifier.padding(16.dp)) {
                                Text(r.title)
                                Text("${r.artist} · not on Spotify · $time", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    IconButton(onClick = { c.shazamHistory.remove(r) }) { Icon(Icons.Default.Close, "Remove") }
                }
            }
        }
    }

    menu?.let { TrackMenuSheet(it, onDismiss = { menu = null }) }
}

/**
 * Fake "app is loading" screen shown during a stealth run: logo + spinner, nothing about listening.
 * Covers everything (taps included); back cancels the run.
 */
@Composable
fun StealthLoader(onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(interactionSource = null, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(160.dp))
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        }
    }
}

fun hasMic(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")
private const val NIGHT_ENDS_HOUR = 6L

private fun nightOf(at: Long): LocalDate =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).minusHours(NIGHT_ENDS_HOUR).toLocalDate()

private fun nightLabel(night: LocalDate): String {
    val today = nightOf(System.currentTimeMillis())
    return when (night) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DAY.format(night)
    }
}
