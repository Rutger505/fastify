package com.rutger.fastify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rutger.fastify.container
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rutger.fastify.ui.LocalNavigator

@Composable
fun SearchScreen() {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val onOpen = nav::openCollection
    val downloaded by c.downloads.trackIds.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf<TrackMenu?>(null) }
    val vm = viewModel { SearchViewModel(c.library) }
    val query by vm.query.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val playerState by c.player.state.collectAsStateWithLifecycle()
    val results = state.results

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { vm.query.value = it },
            placeholder = { Text("What do you want to listen to?") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Clear, "Clear") }
            },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        when {
            state.loading && results == null -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> ErrorCard("Search failed", state.error!!)
        }
        if (results != null) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (results.tracks.isNotEmpty()) {
                    item { SectionHeader("Songs") }
                    itemsIndexed(results.tracks, key = { _, t -> "t" + t.id }) { i, t ->
                        TrackRow(t, playing = playerState.current?.id == t.id, onClick = { c.player.play(results.tracks, i) },
                            downloaded = t.id in downloaded, onMore = { menu = TrackMenu(t) })
                    }
                }
                if (results.albums.isNotEmpty()) {
                    item { SectionHeader("Albums") }
                    items(results.albums, key = { "a" + it.id }) { CollectionRow(it) { onOpen(it) } }
                }
                if (results.playlists.isNotEmpty()) {
                    item { SectionHeader("Playlists") }
                    items(results.playlists, key = { "p" + it.id }) { CollectionRow(it) { onOpen(it) } }
                }
            }
        }
    }

    menu?.let { TrackMenuSheet(it, onDismiss = { menu = null }) }
}
