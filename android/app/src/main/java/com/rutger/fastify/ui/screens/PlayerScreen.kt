package com.rutger.fastify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.rutger.fastify.container
import com.rutger.fastify.playback.PlayerConnection
import com.rutger.fastify.playback.PlayerUiState
import com.rutger.fastify.ui.theme.Green

@Composable
fun MiniPlayer(state: PlayerUiState, player: PlayerConnection, onOpen: () -> Unit) {
    val track = state.current ?: return
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track.imageUrl, 44.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(track.artists, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = player::togglePlay) {
                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "Pause" else "Play")
            }
            IconButton(onClick = player::next) { Icon(Icons.Default.SkipNext, "Next") }
        }
        LinearProgressIndicator(
            progress = { if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.onSurface,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
            gapSize = 0.dp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onClose: () -> Unit) {
    val player = LocalContext.current.container.player
    val state by player.state.collectAsStateWithLifecycle()
    var showQueue by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val track = state.current

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue") }
        }
        Spacer(Modifier.weight(0.5f))
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Artwork(track?.imageUrl, 1000.dp, modifier = Modifier.fillMaxSize(), corner = 8.dp)
        }
        Spacer(Modifier.height(28.dp))
        Text(track?.title.orEmpty(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(track?.artists.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))

        val duration = state.durationMs.coerceAtLeast(1)
        Slider(
            value = if (dragging) dragValue else (state.positionMs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                player.seekTo((dragValue * duration).toLong())
                dragging = false
            },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(if (dragging) (dragValue * duration).toLong() else state.positionMs),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(formatTime(state.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = player::toggleShuffle) {
                Icon(Icons.Default.Shuffle, "Shuffle", tint = if (state.shuffle) Green else MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = player::previous, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.SkipPrevious, "Previous", modifier = Modifier.size(36.dp))
            }
            FilledIconButton(onClick = player::togglePlay, modifier = Modifier.size(72.dp)) {
                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (state.isPlaying) "Pause" else "Play", modifier = Modifier.size(40.dp))
            }
            IconButton(onClick = player::next, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.SkipNext, "Next", modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = player::cycleRepeat) {
                Icon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    "Repeat",
                    tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) Green else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }

    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }) {
            val listState = rememberLazyListState()
            LaunchedEffect(Unit) { listState.scrollToItem(state.index.coerceAtLeast(0)) }
            Text("Queue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            LazyColumn(state = listState) {
                itemsIndexed(state.queue, key = { i, t -> "$i-${t.id}" }) { i, t ->
                    TrackRow(t, playing = i == state.index, onClick = { player.playQueueIndex(i) })
                }
            }
        }
    }
}
