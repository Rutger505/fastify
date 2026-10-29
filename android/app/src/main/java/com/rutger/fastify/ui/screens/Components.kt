package com.rutger.fastify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
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

@Composable
fun TrackRow(track: Track, playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            Text(
                track.artists,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun CollectionRow(c: Collection, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(c.imageUrl, 56.dp, liked = c.kind == CollectionKind.LIKED)
        Column(Modifier.weight(1f)) {
            Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (c.subtitle.isNotEmpty()) {
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

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
