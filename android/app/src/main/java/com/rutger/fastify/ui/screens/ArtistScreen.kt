package com.rutger.fastify.ui.screens

import android.text.Html
import java.text.NumberFormat
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.rutger.fastify.container
import com.rutger.fastify.ui.LocalNavigator

/** Popular tracks shown at first, and how many more each "Show more" adds. */
private const val POPULAR_STEP = 5

private val streamsFormat: NumberFormat = NumberFormat.getIntegerInstance()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(artistId: String) {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val vm = viewModel(key = "artist_$artistId") { ArtistViewModel(c.library, artistId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val playerState by c.player.state.collectAsStateWithLifecycle()
    val downloaded by c.downloads.trackIds.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf<TrackMenu?>(null) }
    var popularShown by remember { mutableIntStateOf(POPULAR_STEP) }
    val artist = state.data

    PullToRefreshBox(isRefreshing = state.refreshing && artist != null, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                // Header: portrait with the name over a dark gradient, like Spotify.
                Box(Modifier.fillMaxWidth().aspectRatio(1.25f)) {
                    if (artist?.imageUrl != null) {
                        AsyncImage(artist.imageUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                    }
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))))
                    IconButton(
                        onClick = { nav.back() },
                        modifier = Modifier.padding(4.dp),
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f), contentColor = Color.White),
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text(
                        artist?.name.orEmpty(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                    )
                }
            }
            state.error?.let { err ->
                item { ErrorCard(if (artist == null) "Couldn't load artist" else "Refresh failed, showing cached artist", err, onRetry = vm::refresh) }
            }
            if (artist == null) {
                if (state.refreshing) item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() } }
                return@LazyColumn
            }
            val top = artist.topTracks
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { if (top.isNotEmpty()) c.player.play(top, 0, shuffle = true, contextUri = "spotify:artist:$artistId") }) { Icon(Icons.Default.Shuffle, "Shuffle") }
                    FilledIconButton(onClick = { if (top.isNotEmpty()) c.player.play(top, 0, contextUri = "spotify:artist:$artistId") }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.PlayArrow, "Play", modifier = Modifier.size(32.dp))
                    }
                }
            }
            if (top.isNotEmpty()) {
                item { SectionHeader("Popular") }
                val shown = top.take(popularShown)
                itemsIndexed(shown, key = { _, t -> "top" + t.id }) { i, t ->
                    TrackRow(
                        t, playing = playerState.current?.id == t.id, onClick = { c.player.play(top, i, contextUri = "spotify:artist:$artistId") },
                        downloaded = t.id in downloaded, onMore = { menu = TrackMenu(t) }, number = i + 1,
                        trailing = artist.playcounts[t.id]?.let { streamsFormat.format(it) },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (top.size > POPULAR_STEP) {
                    // "Show less" stays on the left, "Show more" (5 at a time) on the right.
                    item(key = "popular_more") {
                        Row(Modifier.fillMaxWidth().animateItem().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (popularShown > POPULAR_STEP) {
                                TextButton(onClick = { popularShown = POPULAR_STEP }) { Text("Show less") }
                            }
                            Spacer(Modifier.weight(1f))
                            if (popularShown < top.size) {
                                TextButton(onClick = { popularShown = (popularShown + POPULAR_STEP).coerceAtMost(top.size) }) { Text("Show more") }
                            }
                        }
                    }
                }
            }
            item { AlbumShelf("Albums", artist.albums, nav::openCollection) }
            item { AlbumShelf("Singles and EPs", artist.singles, nav::openCollection) }
            item { AlbumShelf("Compilations", artist.compilations, nav::openCollection) }
            item { AlbumShelf("Appears on", artist.appearsOn, nav::openCollection) }
            artist.biography?.let { bio ->
                item {
                    SectionHeader("About")
                    var expanded by remember { mutableStateOf(false) }
                    val text = remember(bio) { Html.fromHtml(bio, Html.FROM_HTML_MODE_COMPACT).toString().trim() }
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.animateContentSize().clickable { expanded = !expanded }.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                }
            }
        }
    }

    menu?.let { TrackMenuSheet(it, onDismiss = { menu = null }) }
}
