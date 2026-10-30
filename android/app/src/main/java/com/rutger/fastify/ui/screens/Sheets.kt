package com.rutger.fastify.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rutger.fastify.AppContainer
import com.rutger.fastify.container
import com.rutger.fastify.data.Collection
import com.rutger.fastify.data.CollectionKind
import com.rutger.fastify.data.Track
import com.rutger.fastify.data.toTrack
import com.rutger.fastify.diag.AppLog
import com.rutger.fastify.ui.LocalNavigator
import com.rutger.fastify.ui.theme.Green
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a track's "⋮" menu is about: the track and where it is shown. */
data class TrackMenu(
    val track: Track,
    /** Playlist/album/liked list the row belongs to, enables "Remove from this playlist". */
    val context: Collection? = null,
    /** Which copy of the track in [context] (0-based), for removing exactly that row. */
    val occurrence: Int = 0,
    /** Opened from the now-playing screen: adds "Show lyrics". */
    val inPlayer: Boolean = false,
    /** Row in the play queue: adds "Remove from queue". */
    val queueIndex: Int? = null,
)

private enum class MenuPage { Main, AddToPlaylist, Artists }

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

/** Runs a library write in the app scope (survives the sheet closing) and reports the result. */
private fun AppContainer.write(context: Context, what: String, done: String, block: suspend () -> Unit) {
    scope.launch {
        val error = try {
            block()
            null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLog.w("Edit", "$what failed", e)
            "$what failed: ${e.javaClass.simpleName}: ${e.message}"
        }
        withContext(Dispatchers.Main) { context.toast(error ?: done) }
    }
}

/**
 * Spotify-style action sheet: slides up from the bottom, drag it down (or tap outside) to close.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FastifySheet(onDismiss: () -> Unit, state: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackMenuSheet(menu: TrackMenu, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(MenuPage.Main) }
    val likedIds by c.library.likedIds.collectAsStateWithLifecycle()
    val username by c.username.collectAsStateWithLifecycle()

    // Rows from old snapshots lack artist/album ids: look them up once.
    var track by remember(menu.track) { mutableStateOf(menu.track) }
    LaunchedEffect(menu.track.id) {
        if (track.artistRefs.isEmpty() || track.albumId == null) {
            runCatching { withContext(Dispatchers.IO) { c.core.tracks(listOf(track.id)).firstOrNull()?.toTrack() } }
                .getOrNull()?.let { track = it }
        }
    }

    fun close(then: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            then()
        }
    }

    FastifySheet(onDismiss = onDismiss, state = sheetState) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track.imageUrl, 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artists, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        when (page) {
            MenuPage.Main -> {
                val liked = likedIds?.contains(track.id) == true
                MenuItem(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    if (liked) "Remove from Liked Songs" else "Add to Liked Songs", tint = if (liked) Green else null) {
                    c.write(context, "Like", if (liked) "Removed from Liked Songs" else "Added to Liked Songs") {
                        c.library.setLiked(track, !liked)
                    }
                    close()
                }
                MenuItem(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist") { page = MenuPage.AddToPlaylist }
                val ctx = menu.context
                if (ctx != null && ctx.kind == CollectionKind.PLAYLIST && (ctx.owner == null || username == null || ctx.owner == username)) {
                    MenuItem(Icons.Default.RemoveCircleOutline, "Remove from this playlist") {
                        c.write(context, "Remove", "Removed from ${ctx.title}") {
                            c.library.removeFromPlaylist(ctx, track, menu.occurrence)
                        }
                        close()
                    }
                }
                if (ctx?.kind == CollectionKind.LIKED) {
                    // Liked Songs is a list too: removing from it is unliking.
                    MenuItem(Icons.Default.RemoveCircleOutline, "Remove from Liked Songs") {
                        c.write(context, "Remove", "Removed from Liked Songs") { c.library.setLiked(track, false) }
                        close()
                    }
                }
                if (menu.queueIndex != null) {
                    MenuItem(Icons.Default.RemoveCircleOutline, "Remove from queue") {
                        c.player.removeFromQueue(menu.queueIndex)
                        close()
                    }
                } else {
                    MenuItem(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue") {
                        c.player.addToQueue(track)
                        context.toast("Added to queue")
                        close()
                    }
                }
                if (menu.inPlayer) {
                    MenuItem(Icons.Default.Lyrics, "Show lyrics") { close { nav.openLyrics() } }
                }
                val album = track.albumCollection()
                if (album != null && !(ctx?.kind == CollectionKind.ALBUM && ctx.id == album.id)) {
                    MenuItem(Icons.Default.Album, "Go to album") { close { nav.openCollection(album) } }
                }
                when (track.artistRefs.size) {
                    0 -> {}
                    1 -> MenuItem(Icons.Default.Person, "Go to artist") { close { nav.openArtist(track.artistRefs[0].id) } }
                    else -> MenuItem(Icons.Default.Person, "Go to artists") { page = MenuPage.Artists }
                }
            }

            MenuPage.Artists -> {
                SheetTitle("Artists")
                track.artistRefs.forEach { a ->
                    MenuItem(Icons.Default.Person, a.name) { close { nav.openArtist(a.id) } }
                }
            }

            MenuPage.AddToPlaylist -> {
                SheetTitle("Add to playlist")
                val playlists = remember(username) { c.library.editablePlaylists(username) }
                LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                    items(playlists, key = { it.id }) { p ->
                        CollectionRow(p) {
                            c.write(context, "Add", "Added to ${p.title}") { c.library.addToPlaylist(p, track) }
                            close()
                        }
                    }
                    if (playlists.isEmpty()) {
                        item { Text("No playlists of your own yet", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
fun MenuItem(icon: ImageVector, label: String, tint: androidx.compose.ui.graphics.Color? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
