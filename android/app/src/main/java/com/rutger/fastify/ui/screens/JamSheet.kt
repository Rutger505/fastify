package com.rutger.fastify.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.rutger.fastify.container
import com.rutger.fastify.core.JamInfo
import com.rutger.fastify.core.JamMember
import com.rutger.fastify.playback.JamController
import com.rutger.fastify.ui.theme.Green

/** Jam sheet, shown app-wide while [JamController.sheet] is set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamSheet() {
    val jam = LocalContext.current.container.jam
    val state by jam.state.collectAsStateWithLifecycle()
    FastifySheet(onDismiss = jam::closeSheet) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Group, null, tint = Green)
            Text("Jam", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 12.dp))
            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            IconButton(onClick = jam::closeSheet) { Icon(Icons.Default.Close, "Close") }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            val current = state.jam
            val invite = state.invite
            when {
                current != null -> JamDetails(current, state.busy, jam)
                invite != null -> InvitePreview(invite, state, jam)
                !state.loaded -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.padding(24.dp)) }
                else -> NoJam(state.busy, jam)
            }
            state.error?.let {
                SelectionContainer {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NoJam(busy: Boolean, jam: JamController) {
    val context = LocalContext.current
    var link by remember { mutableStateOf("") }
    Text("Listen together: start a Jam on this phone, or open someone's invite link.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 12.dp))
    Button(onClick = jam::start, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Start a Jam") }
    HorizontalDivider(Modifier.padding(vertical = 16.dp))
    Text("Join a Jam", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    OutlinedTextField(
        value = link, onValueChange = { link = it },
        label = { Text("Invite link") }, singleLine = true,
        trailingIcon = {
            IconButton(onClick = { clipboardText(context)?.let { link = it } }) { Icon(Icons.Default.ContentPaste, "Paste") }
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
    OutlinedButton(onClick = { jam.openInvite(link) }, enabled = !busy && link.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Open invite") }
}

@Composable
private fun InvitePreview(invite: String, state: com.rutger.fastify.playback.JamUiState, jam: JamController) {
    val preview = state.preview
    Text(when {
        preview == null -> "Opening invite…"
        preview.hostName.isNotBlank() -> "Join ${preview.hostName}'s Jam"
        else -> "Join this Jam"
    }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    preview?.members?.forEach { MemberRow(it) }
    Spacer(Modifier.height(12.dp))
    Button(onClick = { jam.join(invite, listenHere = true) }, enabled = !state.busy && preview != null, modifier = Modifier.fillMaxWidth()) {
        Text("Listen on this phone")
    }
    OutlinedButton(onClick = { jam.join(invite, listenHere = false) }, enabled = !state.busy && preview != null, modifier = Modifier.fillMaxWidth()) {
        Text("Play on the host's speaker")
    }
    TextButton(onClick = jam::closeSheet, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}

@Composable
private fun JamDetails(jam: JamInfo, busy: Boolean, controller: JamController) {
    val context = LocalContext.current
    var confirmEnd by remember { mutableStateOf(false) }
    Text(when {
        jam.isHost -> "You're hosting"
        else -> "In ${jam.hostName.ifBlank { "the host" }}'s Jam"
    }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(buildString {
        append("${jam.members.size}${if (jam.maxMemberCount > 0u) " / ${jam.maxMemberCount}" else ""} people")
        if (!jam.isHost) append(if (jam.listeningHere) " · playing on this phone" else " · playing on ${jam.hostDeviceName.ifBlank { "the host's device" }}")
        if (jam.queueOnlyMode) append(" · guests can only add songs")
    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (jam.inviteUrl.isNotBlank()) {
        val bitmap = remember(jam.inviteUrl) { qrBitmap(jam.inviteUrl) }
        if (bitmap != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(bitmap.asImageBitmap(), "Jam invite QR code", Modifier.padding(16.dp).size(200.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            OutlinedButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Jam invite", jam.inviteUrl))
            }) { Icon(Icons.Default.ContentCopy, null); Text(" Copy link") }
            Spacer(Modifier.size(12.dp))
            OutlinedButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, jam.inviteUrl)
                }, "Invite to Jam"))
            }) { Icon(Icons.Default.Share, null); Text(" Invite") }
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    jam.members.forEach { member ->
        MemberRow(member, onRemove = if (jam.isHost && !member.isCurrentUser && !busy) { { controller.kick(member.id) } } else null)
    }
    if (jam.isHost) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Only add songs", fontWeight = FontWeight.Medium)
                Text("Guests can add to the queue but not play, skip or reorder", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = jam.queueOnlyMode, onCheckedChange = controller::setQueueOnly, enabled = !busy)
        }
    }
    OutlinedButton(onClick = { if (jam.isHost) confirmEnd = true else controller.leave() }, enabled = !busy,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(if (jam.isHost) "End Jam" else "Leave Jam")
    }
    if (confirmEnd) AlertDialog(
        onDismissRequest = { confirmEnd = false },
        title = { Text("End Jam?") },
        text = { Text("Ends it for everyone.") },
        confirmButton = { TextButton(onClick = { confirmEnd = false; controller.leave() }) { Text("End") } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Cancel") } },
    )
}

@Composable
private fun MemberRow(member: JamMember, onRemove: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (member.imageUrl.isNotBlank()) {
            AsyncImage(member.imageUrl, null, Modifier.size(36.dp).clip(CircleShape))
        } else {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Person, null, tint = if (member.isCurrentUser) Green else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(member.name + when {
                member.isHost && member.isCurrentUser -> " (you, host)"
                member.isHost -> " (host)"
                member.isCurrentUser -> " (you)"
                else -> ""
            }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!member.isListening) {
                Text("Not listening", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
    }
}

private fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

private fun qrBitmap(value: String): Bitmap? = runCatching {
    val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 512, 512,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
    val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) Color.BLACK else Color.WHITE }
    Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}.getOrNull()
