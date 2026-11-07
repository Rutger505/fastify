package com.rutger.fastify.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rutger.fastify.container
import com.rutger.fastify.ui.theme.Green

/** Spotify Connect picker: this phone or another device on the account. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(onDismiss: () -> Unit) {
    val c = LocalContext.current.container
    val remote by c.remote.state.collectAsStateWithLifecycle()
    val target by c.remote.target.collectAsStateWithLifecycle()
    val switching by c.remote.switching.collectAsStateWithLifecycle()
    // While switching, the destination is the selected one (with a spinner), not the old device.
    val selectedId = switching?.let { it.to ?: PHONE } ?: target ?: PHONE
    FastifySheet(onDismiss = onDismiss) {
        SheetTitle("Connect to a device")
        DeviceRow(Icons.Default.Smartphone, "This phone", null, selected = selectedId == PHONE,
            busy = switching.let { it != null && it.to == null }) {
            c.player.transferHere()
            onDismiss()
        }
        val s = remote
        when {
            s == null -> Hint("Looking for devices…")
            s.error != null -> Hint("Connect: ${s.error}", error = true)
            s.devices.isEmpty() -> Hint("No other devices. Open Spotify on your PC.")
        }
        s?.devices?.forEach { d ->
            val active = d.id == s.activeDeviceId
            val status = when {
                active && s.isPlaying -> "Playing"
                active -> "Paused"
                else -> null
            }
            DeviceRow(kindIcon(d.kind), d.name, status, selected = selectedId == d.id, busy = switching?.to == d.id) {
                c.player.transferTo(d.id)
                onDismiss()
            }
            if (target == d.id) VolumeRow(d.volume.toInt()) { c.remote.setVolume(d.id, it) }
        }
        Spacer(Modifier.padding(bottom = 16.dp))
    }
}

@Composable
private fun DeviceRow(icon: ImageVector, name: String, status: String?, selected: Boolean, busy: Boolean, onClick: () -> Unit) {
    val tint = if (selected) Green else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = tint, fontWeight = if (selected) FontWeight.SemiBold else null)
            (if (busy) "Switching…" else status)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Green, strokeWidth = 2.dp)
    }
}

@Composable
private fun VolumeRow(volume: Int, onChange: (Int) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(0f) }
    Row(Modifier.fillMaxWidth().padding(start = 64.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, "Volume", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Slider(
            value = if (dragging) value else volume / 65535f,
            onValueChange = { dragging = true; value = it },
            onValueChangeFinished = { onChange((value * 65535).toInt()); dragging = false },
        )
    }
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

/** [selectedId] for this phone (device ids are hex, never this). */
private const val PHONE = "phone"

private fun kindIcon(kind: String): ImageVector = when (kind) {
    "computer" -> Icons.Default.Computer
    "smartphone", "tablet" -> Icons.Default.Smartphone
    "speaker", "avr", "stb", "audio_dongle" -> Icons.Default.Speaker
    "tv", "cast_video" -> Icons.Default.Tv
    else -> Icons.Default.DevicesOther
}
