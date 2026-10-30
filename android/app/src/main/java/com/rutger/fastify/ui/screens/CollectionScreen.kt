package com.rutger.fastify.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rutger.fastify.container
import com.rutger.fastify.data.Collection
import com.rutger.fastify.data.CollectionKind
import com.rutger.fastify.data.Track
import com.rutger.fastify.ui.LocalNavigator
import com.rutger.fastify.ui.theme.Green

/** A row as shown: the track plus which copy of it this is in the full list (for removal). */
private data class ShownRow(val track: Track, val occurrence: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(collection: Collection) {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val vm = viewModel(key = collection.cacheKey) { CollectionViewModel(c.library, c.downloads, collection) }
    val state by vm.state.collectAsStateWithLifecycle()
    val playerState by c.player.state.collectAsStateWithLifecycle()
    val downloadedIds by c.downloads.trackIds.collectAsStateWithLifecycle()
    val pinned by c.downloads.collections.collectAsStateWithLifecycle()
    val progress by c.downloads.progress.collectAsStateWithLifecycle()
    val isPinned = pinned.any { it.cacheKey == collection.cacheKey }
    val tracks = state.data.orEmpty()
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf<TrackMenu?>(null) }
    var confirmRemoveDownload by remember { mutableStateOf(false) }

    val rows = remember(tracks) {
        val seen = HashMap<String, Int>()
        tracks.map { t -> ShownRow(t, seen.merge(t.id, 1) { a, b -> a + b }!! - 1) }
    }
    val shown = remember(rows, query) {
        val q = query.trim()
        if (q.isEmpty()) rows else rows.filter {
            it.track.title.contains(q, ignoreCase = true) ||
                it.track.artists.contains(q, ignoreCase = true) ||
                it.track.album.contains(q, ignoreCase = true)
        }
    }
    val shownTracks = remember(shown) { shown.map { it.track } }
    val header = if (collection.kind == CollectionKind.ALBUM) remember(state.data) { c.library.albumHeader(collection.id) } else null

    PullToRefreshBox(isRefreshing = state.refreshing && state.data != null, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }, modifier = Modifier.padding(4.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                    // Spotify's "Find in playlist".
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(if (collection.kind == CollectionKind.ALBUM) "Find in album" else "Find in playlist") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear") }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).height(52.dp),
                    )
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Artwork(collection.imageUrl, 200.dp, liked = collection.kind == CollectionKind.LIKED)
                    Spacer(Modifier.height(16.dp))
                    Text(collection.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
                    if (header != null && header.artists.isNotEmpty()) {
                        // Album artists link to their pages.
                        Row(Modifier.padding(top = 4.dp)) {
                            header.artists.forEachIndexed { i, a ->
                                Text(
                                    a.name + if (i < header.artists.lastIndex) ", " else "",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { nav.openArtist(a.id) },
                                )
                            }
                        }
                    }
                    val subtitle = if (header != null) {
                        listOfNotNull(header.type.replaceFirstChar { it.uppercase() }.takeIf { it.isNotEmpty() }, header.year?.toString())
                    } else {
                        listOf(collection.subtitle)
                    }
                    Text(
                        (subtitle + listOf(if (state.data != null) "${tracks.size} songs" else ""))
                            .filter { it.isNotEmpty() }.joinToString(" • "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                val p = progress[collection.cacheKey]
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        if (isPinned) confirmRemoveDownload = true
                        else if (tracks.isNotEmpty()) c.downloads.pin(collection, tracks)
                    }) {
                        if (isPinned && p != null && !p.complete) {
                            CircularProgressIndicator(
                                progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                                modifier = Modifier.size(24.dp), color = Green, strokeWidth = 3.dp,
                            )
                        } else if (isPinned) {
                            Icon(Icons.Default.ArrowCircleDown, "Remove download", tint = Green, modifier = Modifier.size(30.dp))
                        } else {
                            Icon(Icons.Outlined.ArrowCircleDown, "Download", modifier = Modifier.size(30.dp))
                        }
                    }
                    if (isPinned && p != null) {
                        Text(
                            when {
                                !p.complete -> "Downloading ${p.done} of ${p.total}"
                                p.failed > 0 -> "Downloaded, ${p.failed} failed (tap to retry)"
                                else -> "Downloaded"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (p.complete && p.failed == 0) Green else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = if (p.complete && p.failed > 0) Modifier.clickable { c.downloads.retryFailed() } else Modifier,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { if (shownTracks.isNotEmpty()) c.player.play(shownTracks, 0, shuffle = true) }) {
                        Icon(Icons.Default.Shuffle, "Shuffle")
                    }
                    Spacer(Modifier.width(4.dp))
                    FilledIconButton(
                        onClick = { if (shownTracks.isNotEmpty()) c.player.play(shownTracks, 0) },
                        modifier = Modifier.size(56.dp),
                    ) { Icon(Icons.Default.PlayArrow, "Play", modifier = Modifier.size(32.dp)) }
                }
            }
            state.error?.let { err ->
                item {
                    ErrorCard(
                        if (state.data == null) "Couldn't load tracks" else "Refresh failed, showing cached tracks",
                        err,
                        onRetry = vm::refresh,
                    )
                }
            }
            if (state.data == null && state.refreshing) {
                item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() } }
            }
            if (query.isNotBlank() && shown.isEmpty() && state.data != null) {
                item {
                    Text("No songs match \"${query.trim()}\"", modifier = Modifier.fillMaxWidth().padding(24.dp),
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(shown, key = { "${it.track.id}-${it.occurrence}" }) { row ->
                val t = row.track
                TrackRow(
                    t,
                    playing = playerState.current?.id == t.id,
                    onClick = { c.player.play(shownTracks, shown.indexOf(row)) },
                    downloaded = t.id in downloadedIds,
                    onMore = { menu = TrackMenu(t, context = collection, occurrence = row.occurrence) },
                )
            }
        }
    }

    menu?.let { TrackMenuSheet(it, onDismiss = { menu = null }) }

    if (confirmRemoveDownload) {
        AlertDialog(
            onDismissRequest = { confirmRemoveDownload = false },
            title = { Text("Remove from downloads?") },
            text = { Text("You won't be able to play this offline anymore.") },
            confirmButton = {
                TextButton(onClick = {
                    c.downloads.unpin(collection)
                    confirmRemoveDownload = false
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveDownload = false }) { Text("Cancel") } },
        )
    }
}
