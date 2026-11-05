package com.rutger.fastify.ui.screens

import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rutger.fastify.container
import com.rutger.fastify.data.Collection
import com.rutger.fastify.ui.LocalNavigator
import java.time.LocalTime

private const val SHELF_RELEASES = 12

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val vm = viewModel { HomeViewModel(c.library) }
    val releasesVm = viewModel { NewReleasesViewModel(c.library) }
    val state by vm.state.collectAsStateWithLifecycle()
    val releases by releasesVm.state.collectAsStateWithLifecycle()
    val playerState by c.player.state.collectAsStateWithLifecycle()
    val pinned by c.downloads.collections.collectAsStateWithLifecycle()
    val pinnedKeys = pinned.map { it.cacheKey }.toSet()
    val home = state.data
    var playlistArtwork by remember { mutableStateOf<Map<String, List<String?>>>(emptyMap()) }
    LaunchedEffect(home?.playlists) {
        val playlists = home?.playlists.orEmpty()
        playlistArtwork = playlists.mapNotNull { playlist ->
            c.library.cachedPlaylistArtwork(playlist)?.let { playlist.id to it }
        }.toMap()
        playlists.forEach { playlist ->
            if (playlist.id !in playlistArtwork) {
                runCatching { c.library.playlistArtwork(playlist) }
                    .onSuccess { images -> playlistArtwork = playlistArtwork + (playlist.id to images) }
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = state.refreshing && home != null,
        onRefresh = { vm.refresh(); releasesVm.refresh(); c.scope.launch { c.updater.check() } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(Modifier.padding(start = 16.dp, top = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(greeting(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = nav::openDiagnostics) { Icon(Icons.Default.BugReport, "Diagnostics") }
                    IconButton(onClick = {
                        c.player.stopAndClear()
                        c.queueStore.clear()
                        c.library.clear()
                        c.history.clear()
                        c.streams.clear()
                        c.downloads.clear()
                        c.forgetUser()
                        c.auth.logout()
                    }) { Icon(Icons.AutoMirrored.Filled.Logout, "Log out") }
                }
            }
            item { UpdateCard(c.updater) }
            state.error?.let { err ->
                item {
                    ErrorCard(
                        if (home == null) "Couldn't load your library" else "Refresh failed, showing cached library",
                        err,
                        onRetry = vm::refresh,
                        onDiagnostics = nav::openDiagnostics,
                    )
                }
            }
            if (home == null) {
                if (state.refreshing) {
                    item { Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) { CircularProgressIndicator() } }
                }
                return@LazyColumn
            }

            if (home.recent.isNotEmpty()) {
                item { SectionHeader("Recently played") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                        items(home.recent, key = { it.id }) { t ->
                            AlbumCard(t.title, t.artists, t.imageUrl,
                                onClick = {
                                    if (playerState.current?.id == t.id) nav.openPlayer()
                                    else c.player.play(home.recent, home.recent.indexOf(t))
                                },
                                highlighted = playerState.current?.id == t.id)
                        }
                    }
                }
            }

            releases.data?.takeIf { it.isNotEmpty() }?.let { list ->
                item { AlbumShelf("New releases for you", list.take(SHELF_RELEASES), nav::openCollection, onTitleClick = nav::openNewReleases) }
            }

            item { SectionHeader("Your library") }
            item { CollectionRow(Collection.Liked, downloaded = Collection.Liked.cacheKey in pinnedKeys) { nav.openCollection(Collection.Liked) } }
            items(home.playlists, key = { "p" + it.id }) { playlist ->
                CollectionRow(
                    playlist,
                    downloaded = playlist.cacheKey in pinnedKeys,
                    playlistArtwork = playlistArtwork[playlist.id] ?: emptyList(),
                ) { nav.openCollection(playlist) }
            }
            if (home.albums.isNotEmpty()) {
                item { SectionHeader("Albums") }
                items(home.albums, key = { "a" + it.id }) { CollectionRow(it, downloaded = it.cacheKey in pinnedKeys) { nav.openCollection(it) } }
            }
        }
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}
