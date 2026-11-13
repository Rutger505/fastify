package com.rutger.fastify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
import com.rutger.fastify.data.Collection
import com.rutger.fastify.data.Track

/** Songs shown above the album/playlist shelves. */
private const val TOP_SONGS = 4

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
    val recent by c.searchHistory.items.collectAsStateWithLifecycle()
    // Save the query once it led somewhere: keyboard search or tapping a result.
    val saveQuery = { c.searchHistory.record(query) }

    // Focus only after a double-tap on Search, not when returning from a page opened here.
    val focus = remember { FocusRequester() }
    var handledTick by rememberSaveable { mutableStateOf(-1) }
    LaunchedEffect(nav.searchFocusTick) {
        if (nav.searchFocusTick != handledTick) {
            handledTick = nav.searchFocusTick
            runCatching { focus.requestFocus() }
        }
    }

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
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { saveQuery() }),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus),
        )
        if (query.isBlank() && recent.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent searches", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { c.searchHistory.clear() }) { Text("Clear") }
                    }
                }
                items(recent, key = { "h" + it }) { q ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.query.value = q }.padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.History, null)
                        Spacer(Modifier.width(16.dp))
                        Text(q, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { c.searchHistory.remove(q) }) { Icon(Icons.Default.Close, "Remove") }
                    }
                }
            }
        }
        when {
            state.loading && results == null -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> ErrorCard("Song search failed", state.error!!)
        }
        state.catalogError?.let { ErrorCard("Album/playlist search failed", it) }
        if (results != null) {
            // A few songs first, then the album/playlist shelves, then the rest of the songs.
            val songs = results.tracks.withIndex().toList()
            val songRow: @Composable (IndexedValue<Track>) -> Unit = { (i, t) ->
                TrackRow(t, playing = playerState.current?.id == t.id, onClick = { saveQuery(); c.player.play(results.tracks, i) },
                    downloaded = t.id in downloaded, onMore = { menu = TrackMenu(t) }, onSwipeQueue = { c.player.addToQueue(t) })
            }
            val open = { col: Collection -> saveQuery(); onOpen(col) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (songs.isNotEmpty()) item { SectionHeader("Songs") }
                items(songs.take(TOP_SONGS), key = { "t" + it.index }) { songRow(it) }
                if (results.playlists.isNotEmpty()) item { AlbumShelf("Playlists", results.playlists, open) }
                if (results.albums.isNotEmpty()) item { AlbumShelf("Albums", results.albums, open) }
                if (songs.size > TOP_SONGS) {
                    item { SectionHeader("More songs") }
                    items(songs.drop(TOP_SONGS), key = { "t" + it.index }) { songRow(it) }
                }
            }
        }
    }

    menu?.let { TrackMenuSheet(it, onDismiss = { menu = null }) }
}
