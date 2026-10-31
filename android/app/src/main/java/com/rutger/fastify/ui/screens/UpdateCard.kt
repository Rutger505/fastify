package com.rutger.fastify.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rutger.fastify.update.UpdateStatus
import com.rutger.fastify.update.Updater

/** Shown on Home only when an update is ready, needs a tap, or failed (with the real error). */
@Composable
fun UpdateCard(updater: Updater) {
    val status by updater.status.collectAsStateWithLifecycle()
    when (val s = status) {
        is UpdateStatus.Ready -> UpdateRow(
            "Update ${s.release.versionName} ready",
            if (s.silent) "Installs by itself when Fastify is in the background and not playing."
            else "Needs one tap: Android asks to confirm the first in-app update.",
            "Install now", updater::installNow,
        )
        is UpdateStatus.NeedsConfirmation -> UpdateRow(
            "Update ${s.release.versionName} ready",
            "Android wants a confirmation for this install.",
            "Install", updater::installNow,
        )
        is UpdateStatus.Installing -> UpdateRow("Installing ${s.release.versionName}", "Fastify restarts when done.", null, null)
        is UpdateStatus.Failed -> if (s.release != null) {
            ErrorCard("Update ${s.release.versionName} failed", s.message, onRetry = updater::installNow)
        }
        else -> Unit
    }
}

@Composable
private fun UpdateRow(title: String, detail: String, action: String?, onAction: (() -> Unit)?) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}
