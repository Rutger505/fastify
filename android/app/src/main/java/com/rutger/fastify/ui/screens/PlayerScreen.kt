package com.rutger.fastify.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.rutger.fastify.container
import com.rutger.fastify.playback.PlayerConnection
import com.rutger.fastify.playback.PlayerUiState
import com.rutger.fastify.ui.LocalNavigator
import com.rutger.fastify.ui.theme.Green
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
fun MiniPlayer(state: PlayerUiState, player: PlayerConnection, onOpen: () -> Unit) {
    val track = state.current ?: return
    // Spotify-style: swipe the track info left for next, right for previous; play/pause on the far right.
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SwipeToSkip(player, Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(track.imageUrl, 44.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(track.artists, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            IconButton(onClick = player::togglePlay) {
                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.isPlaying) "Pause" else "Play")
            }
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

private const val SWIPE_FRACTION = 0.3f
private const val SWIPE_VELOCITY = 1500f
private const val CLOSE_FRACTION = 0.25f

/**
 * Horizontal swipe on [content]: left = next track, right = previous track. The content follows
 * the finger, flies out, and the new track slides in from the other side.
 */
@Composable
fun SwipeToSkip(player: PlayerConnection, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var drag by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(1) }
    Box(
        modifier
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .clipToBounds()
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { drag += it },
                onDragStopped = { velocity ->
                    val w = width.toFloat()
                    val dir = when {
                        drag < -w * SWIPE_FRACTION || velocity < -SWIPE_VELOCITY -> -1
                        drag > w * SWIPE_FRACTION || velocity > SWIPE_VELOCITY -> 1
                        else -> 0
                    }
                    if (dir == 0) {
                        animate(drag, 0f) { v, _ -> drag = v }
                    } else {
                        animate(drag, dir * w, animationSpec = tween(120)) { v, _ -> drag = v }
                        if (dir < 0) player.next() else player.previousTrack()
                        animate(-dir * w, 0f, animationSpec = tween(200)) { v, _ -> drag = v }
                    }
                },
            ),
    ) {
        Box(
            Modifier.graphicsLayer {
                translationX = drag
                alpha = 1f - (abs(drag) / width).coerceIn(0f, 1f) * 0.7f
            },
        ) { content() }
    }
}

/**
 * The line being sung right under the cover (plus the next one, dimmed); tap for full lyrics.
 * Takes no space when the track has no synced lyrics, so the cover keeps its size.
 */
@Composable
private fun LyricsPreview(trackId: String?, onOpen: () -> Unit) {
    val lyrics = (rememberLyrics(trackId) as? LyricsState.Loaded)?.lyrics?.takeIf { it.synced && it.lines.isNotEmpty() } ?: return
    val position = rememberPlaybackPosition()
    val current = lyrics.currentLine(position)
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).clickable(onClick = onOpen)) {
        // New lines come in from the bottom and push the old one out the top.
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
            },
            label = "lyric line",
        ) { i ->
            Column {
                Text(
                    lyrics.lines.getOrNull(i)?.words?.ifBlank { null } ?: "♪",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    lyrics.lines.getOrNull(i + 1)?.words.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onClose: () -> Unit) {
    val c = LocalContext.current.container
    val player = c.player
    val nav = LocalNavigator.current
    val state by player.state.collectAsStateWithLifecycle()
    val likedIds by c.library.likedIds.collectAsStateWithLifecycle()
    var showQueue by remember { mutableStateOf(false) }
    var showArtists by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<TrackMenu?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val track = state.current
    // Drag the whole screen down to close it, like Spotify's now-playing view.
    var dragY by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableIntStateOf(1) }

    Column(
        Modifier
            .fillMaxSize()
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .graphicsLayer { translationY = dragY }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { dragY = (dragY + it).coerceAtLeast(0f) },
                onDragStopped = { velocity ->
                    if (dragY > height * CLOSE_FRACTION || velocity > SWIPE_VELOCITY) {
                        animate(dragY, height.toFloat(), animationSpec = tween(150)) { v, _ -> dragY = v }
                        onClose()
                    } else {
                        animate(dragY, 0f) { v, _ -> dragY = v }
                    }
                },
            )
            .padding(horizontal = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close") }
            Text(
                track?.album.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { track?.let { menu = TrackMenu(it, inPlayer = true) } }) {
                Icon(Icons.Default.MoreVert, "More options")
            }
        }
        Spacer(Modifier.height(24.dp))
        // Square art that shrinks on short screens so the controls and bottom bar always fit.
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        SwipeToSkip(player, Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true)) {
            Artwork(track?.imageUrl, 1000.dp, modifier = Modifier.fillMaxSize(), corner = 8.dp)
        }
        }
        LyricsPreview(track?.id, onOpen = nav::openLyrics)
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // Title opens the album, artists open the artist, like Spotify.
                Text(track?.title.orEmpty(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(enabled = track?.albumId != null) { track?.albumCollection()?.let(nav::openCollection) })
                Text(track?.artists.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(enabled = !track?.artistRefs.isNullOrEmpty()) {
                        val refs = track?.artistRefs.orEmpty()
                        if (refs.size == 1) nav.openArtist(refs[0].id) else showArtists = true
                    })
            }
            if (track != null) {
                val liked = likedIds?.contains(track.id) == true
                IconButton(onClick = {
                    c.scope.launch {
                        runCatching { c.library.setLiked(track, !liked) }
                            .onFailure { e -> withContext(Dispatchers.Main) { c.app.toast("Like failed: ${e.message}") } }
                    }
                }) {
                    Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        if (liked) "Remove from Liked Songs" else "Add to Liked Songs",
                        tint = if (liked) Green else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
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
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
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
        Spacer(Modifier.height(8.dp))
        // Bottom bar: lyrics left, queue right (Spotify puts the queue button down here too).
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (track != null) nav.openLyrics() }) { Icon(Icons.Default.Lyrics, "Lyrics") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue") }
        }
    }

    if (showQueue) {
        FastifySheet(onDismiss = { showQueue = false }) {
            val listState = rememberLazyListState()
            LaunchedEffect(Unit) { listState.scrollToItem(state.index.coerceAtLeast(0)) }
            val autoplay by c.settings.autoplay.collectAsStateWithLifecycle()
            SheetTitle("Queue")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Autoplay", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Keep playing similar songs when the queue ends",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoplay, onCheckedChange = c.settings::setAutoplay)
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
                itemsIndexed(state.queue, key = { i, t -> "$i-${t.id}" }) { i, t ->
                    if (i == state.autoplayFrom) {
                        Text(
                            "Recommended",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    TrackRow(t, playing = i == state.index, onClick = { player.playQueueIndex(i) },
                        onMore = { menu = TrackMenu(t, queueIndex = i.takeIf { it != state.index }) })
                }
            }
        }
    }

    if (showArtists && track != null) {
        FastifySheet(onDismiss = { showArtists = false }) {
            SheetTitle("Artists")
            track.artistRefs.forEach { a ->
                MenuItem(Icons.Default.Person, a.name) {
                    showArtists = false
                    nav.openArtist(a.id)
                }
            }
        }
    }

    menu?.let { TrackMenuSheet(it, onDismiss = { menu = null }) }
}
