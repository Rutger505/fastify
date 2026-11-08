package com.rutger.fastify.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rutger.fastify.update.UpdateStatus
import com.rutger.fastify.update.Updater

/** One compact row on Home whenever a newer version exists; failures show the real error. */
@Composable
fun UpdateCard(updater: Updater) {
    val status by updater.status.collectAsStateWithLifecycle()
    when (val s = status) {
        is UpdateStatus.Downloading -> UpdateRow("v${s.release.versionCode} available", busy = true)
        is UpdateStatus.Ready -> UpdateRow("v${s.release.versionCode} available", onInstall = updater::installNow)
        is UpdateStatus.Scheduled -> UpdateRow("v${s.release.versionCode} available", onInstall = updater::installNow)
        is UpdateStatus.NeedsConfirmation -> UpdateRow("v${s.release.versionCode} available", onInstall = updater::installNow)
        is UpdateStatus.Installing -> UpdateRow("Installing v${s.release.versionCode}", busy = true)
        is UpdateStatus.Failed -> if (s.release != null) {
            ErrorCard("Update v${s.release.versionCode} failed", s.message, onRetry = updater::installNow)
        }
        else -> Unit
    }
}

@Composable
private fun UpdateRow(text: String, onInstall: (() -> Unit)? = null, busy: Boolean = false) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.height(48.dp).padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(20.dp), strokeWidth = 2.dp)
            else if (onInstall != null) Button(onClick = onInstall, Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Install") }
        }
    }
}
