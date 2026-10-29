package com.rutger.fastify.ui.screens

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rutger.fastify.container
import com.rutger.fastify.data.Collection
import com.rutger.fastify.data.CollectionKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(collection: Collection, onBack: () -> Unit) {
    val c = LocalContext.current.container
    val vm = viewModel(key = collection.cacheKey) { CollectionViewModel(c.library, collection) }
    val state by vm.state.collectAsStateWithLifecycle()
    val playerState by c.player.state.collectAsStateWithLifecycle()
    val tracks = state.data.orEmpty()

    PullToRefreshBox(isRefreshing = state.refreshing && state.data != null, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            }
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Artwork(collection.imageUrl, 200.dp, liked = collection.kind == CollectionKind.LIKED)
                    Spacer(Modifier.height(16.dp))
                    Text(collection.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    Text(
                        listOf(collection.subtitle, if (state.data != null) "${tracks.size} songs" else "")
                            .filter { it.isNotEmpty() }.joinToString(" • "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { if (tracks.isNotEmpty()) c.player.play(tracks, 0, shuffle = true) }) {
                        Icon(Icons.Default.Shuffle, "Shuffle")
                    }
                    FilledIconButton(
                        onClick = { if (tracks.isNotEmpty()) c.player.play(tracks, 0) },
                        modifier = Modifier.size(56.dp),
                    ) { Icon(Icons.Default.PlayArrow, "Play", modifier = Modifier.size(32.dp)) }
                }
            }
            state.error?.let { err ->
                item {
                    Text(
                        if (state.data == null) "Couldn't load: $err" else "Offline – showing cached tracks",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            if (state.data == null && state.refreshing) {
                item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() } }
            }
            itemsIndexed(tracks, key = { i, t -> "$i-${t.id}" }) { i, t ->
                TrackRow(t, playing = playerState.current?.id == t.id, onClick = { c.player.play(tracks, i) })
            }
        }
    }
}
