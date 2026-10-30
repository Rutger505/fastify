package com.rutger.fastify.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rutger.fastify.container
import com.rutger.fastify.data.LyricsData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data class Error(val message: String) : LyricsState
    data class Loaded(val lyrics: LyricsData) : LyricsState
}

/** Full-screen lyrics for the current track. Synced lyrics follow playback; tap a line to seek. */
@Composable
fun LyricsScreen(onClose: () -> Unit) {
    val c = LocalContext.current.container
    val player = c.player
    val playerState by player.state.collectAsStateWithLifecycle()
    val track = playerState.current
    var lyrics by remember { mutableStateOf<LyricsState>(LyricsState.Loading) }
    var position by remember { mutableLongStateOf(0L) }

    LaunchedEffect(track?.id) {
        val id = track?.id ?: return@LaunchedEffect
        lyrics = LyricsState.Loading
        lyrics = try {
            c.library.lyrics(id)?.let { LyricsState.Loaded(it) } ?: LyricsState.None
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LyricsState.Error("${e.javaClass.simpleName}: ${e.message}")
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            position = player.position()
            delay(200)
        }
    }

    val background = Color(0xFF3A3A5C)
    Column(Modifier.fillMaxSize().background(background)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(track?.title.orEmpty(), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track?.artists.orEmpty(), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = player::togglePlay) {
                Icon(if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause", tint = Color.White)
            }
        }
        when (val s = lyrics) {
            LyricsState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Color.White) }
            LyricsState.None -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("No lyrics for this song", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            is LyricsState.Error -> ErrorCard("Couldn't load lyrics", s.message)
            is LyricsState.Loaded -> LyricsLines(s.lyrics, position, onSeek = player::seekTo)
        }
    }
}

@Composable
private fun LyricsLines(lyrics: LyricsData, position: Long, onSeek: (Long) -> Unit) {
    val lines = lyrics.lines
    val current = if (lyrics.synced) lines.indexOfLast { it.startMs <= position } else -1
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        // Keep the current line in the upper third, like Spotify.
        if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp), modifier = Modifier.fillMaxSize()) {
        itemsIndexed(lines) { i, line ->
            val color by animateColorAsState(
                when {
                    !lyrics.synced -> Color.White
                    i == current -> Color.White
                    i < current -> Color.White.copy(alpha = 0.55f)
                    else -> Color.Black.copy(alpha = 0.55f)
                },
                label = "lyric",
            )
            Text(
                line.words.ifBlank { "♪" },
                color = color,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
                    .then(if (lyrics.synced) Modifier.clickable { onSeek(line.startMs) } else Modifier)
                    .padding(vertical = 8.dp),
            )
        }
        item {
            Text("Lyrics provided by ${lyrics.provider}", color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 24.dp, bottom = 32.dp))
        }
    }
}
