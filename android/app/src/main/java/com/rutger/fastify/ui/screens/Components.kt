package com.rutger.fastify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.rutger.fastify.data.Collection
import com.rutger.fastify.data.CollectionKind
import com.rutger.fastify.data.Track
import com.rutger.fastify.ui.theme.Green

@Composable
fun Artwork(url: String?, size: Dp, modifier: Modifier = Modifier, liked: Boolean = false, corner: Dp = 4.dp) {
    val shape = RoundedCornerShape(corner)
    when {
        liked -> Box(
            modifier.size(size).clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFF4A2FBD), Color(0xFF8FA3E8)))),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Favorite, null, tint = Color.White, modifier = Modifier.size(size / 2.5f)) }

        url == null -> Box(
            modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }

        else -> AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

/** Green "downloaded" arrow, as in Spotify's track rows. */
@Composable
fun DownloadedMark(size: Dp = 14.dp) {
    Icon(Icons.Default.ArrowCircleDown, "Downloaded", tint = Green, modifier = Modifier.size(size))
    Spacer(Modifier.width(4.dp))
}

@Composable
fun TrackRow(
    track: Track,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    downloaded: Boolean = false,
    onMore: (() -> Unit)? = null,
    number: Int? = null,
) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = 16.dp, end = if (onMore != null) 0.dp else 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Text("$number", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp))
        }
        Artwork(track.imageUrl, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (playing) Green else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (downloaded) DownloadedMark()
                Text(
                    track.artists,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (onMore != null) {
            IconButton(onClick = onMore) {
                Icon(Icons.Default.MoreVert, "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun CollectionRow(c: Collection, downloaded: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(c.imageUrl, 56.dp, liked = c.kind == CollectionKind.LIKED)
        Column(Modifier.weight(1f)) {
            Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (c.subtitle.isNotEmpty() || downloaded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (downloaded) DownloadedMark()
                    Text(
                        c.subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** Square cover with title + subtitle, for horizontal shelves (releases, recently played). */
@Composable
fun AlbumCard(title: String, subtitle: String, imageUrl: String?, onClick: () -> Unit, highlighted: Boolean = false) {
    Column(Modifier.width(144.dp).padding(end = 12.dp).clickable(onClick = onClick)) {
        Artwork(imageUrl, 132.dp)
        Spacer(Modifier.height(6.dp))
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AlbumShelf(title: String, albums: List<Collection>, onOpen: (Collection) -> Unit, onTitleClick: (() -> Unit)? = null) {
    if (albums.isEmpty()) return
    SectionHeader(title, onClick = onTitleClick)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
        items(albums, key = { it.id }) { a -> AlbumCard(a.title, a.subtitle, a.imageUrl, onClick = { onOpen(a) }) }
    }
}

@Composable
fun SectionHeader(text: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (onClick != null) {
            Text("Show all", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
