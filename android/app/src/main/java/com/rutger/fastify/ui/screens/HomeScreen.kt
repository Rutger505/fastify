package com.rutger.fastify.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rutger.fastify.container
import com.rutger.fastify.data.Collection
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (Collection) -> Unit) {
    val c = LocalContext.current.container
    val vm = viewModel { HomeViewModel(c.library) }
    val state by vm.state.collectAsStateWithLifecycle()
    val playerState by c.player.state.collectAsStateWithLifecycle()
    val home = state.data

    PullToRefreshBox(isRefreshing = state.refreshing && home != null, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(Modifier.padding(start = 16.dp, top = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(greeting(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        c.player.stopAndClear()
                        c.queueStore.clear()
                        c.library.clear()
                        c.streams.clear()
                        c.auth.logout()
                    }) { Icon(Icons.AutoMirrored.Filled.Logout, "Log out") }
                }
            }
            state.error?.let { err ->
                item {
                    Text(
                        if (home == null) "Couldn't load your library: $err" else "Offline – showing cached library",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (home == null) return@LazyColumn

            if (home.recent.isNotEmpty()) {
                item { SectionHeader("Recently played") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                        items(home.recent, key = { it.id }) { t ->
                            Column(
                                Modifier.width(132.dp).padding(end = 12.dp)
                                    .clickable { c.player.play(home.recent, home.recent.indexOf(t)) },
                            ) {
                                Artwork(t.imageUrl, 120.dp)
                                Spacer(Modifier.height(6.dp))
                                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                                    color = if (playerState.current?.id == t.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Text(t.artists, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            item { SectionHeader("Your library") }
            item { CollectionRow(Collection.Liked) { onOpen(Collection.Liked) } }
            items(home.playlists, key = { "p" + it.id }) { CollectionRow(it) { onOpen(it) } }
            if (home.albums.isNotEmpty()) {
                item { SectionHeader("Albums") }
                items(home.albums, key = { "a" + it.id }) { CollectionRow(it) { onOpen(it) } }
            }
        }
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}
