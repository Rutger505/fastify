package com.rutger.fastify.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rutger.fastify.container
import com.rutger.fastify.data.LibraryRepository
import com.rutger.fastify.data.ShazamHistory
import com.rutger.fastify.data.ShazamResult
import com.rutger.fastify.diag.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
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
 */
class ShazamViewModel(private val repo: LibraryRepository, private val history: ShazamHistory) : ViewModel() {
    private val _state = MutableStateFlow<ShazamState>(ShazamState.Idle)
    val state: StateFlow<ShazamState> = _state
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.value = ShazamState.Listening(0, false)
            try {
                _state.value = listen()?.let { history.record(it); ShazamState.Found(it) } ?: ShazamState.NoMatch
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.w("Shazam", "recognize failed", e)
                _state.value = ShazamState.Failed("${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    fun stop() {
        job?.cancel()
        _state.value = ShazamState.Idle
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
fun ShazamScreen() {
    val ctx = LocalContext.current
    val c = ctx.container
    val vm = viewModel { ShazamViewModel(c.library, c.shazamHistory) }
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
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> vm.start()
            else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(onClick = onButton, modifier = Modifier.size(140.dp)) {
                    Icon(if (listening) Icons.Default.Stop else Icons.Default.GraphicEq, if (listening) "Stop" else "Listen", Modifier.size(72.dp))
                }
                Spacer(Modifier.height(16.dp))
                when (val s = state) {
                    ShazamState.Idle -> Text("Tap to Shazam")
                    is ShazamState.Listening -> {
                        Text(if (s.asking) "Asking Shazam… (${s.heardMs / 1000} s heard)" else "Listening… ${s.heardMs / 1000} s")
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { s.heardMs / 12_000f }, modifier = Modifier.fillMaxWidth(0.6f))
                    }
                    ShazamState.NoMatch -> Text("No match after 12 s. Get closer to the speaker and try again.", textAlign = TextAlign.Center)
                    is ShazamState.Found -> {}
                    is ShazamState.Failed -> ErrorCard("Shazam failed", s.message, onRetry = onButton)
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
                    downloaded = t.id in downloaded, onMore = { menu = TrackMenu(t) })
            }
        }
        val past = found.filterNot { (state as? ShazamState.Found)?.result == it }
        if (past.isNotEmpty()) {
            item { SectionHeader("Shazamed") }
            items(past, key = { "h" + it.at }) { r ->
                val t = r.tracks.firstOrNull()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (t != null) {
                            TrackRow(t, playing = playerState.current?.id == t.id, onClick = { c.player.play(listOf(t), 0) },
                                downloaded = t.id in downloaded, onMore = { menu = TrackMenu(t) })
                        } else {
                            Column(Modifier.padding(16.dp)) {
                                Text(r.title)
                                Text("${r.artist} · not on Spotify", style = MaterialTheme.typography.bodySmall)
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
