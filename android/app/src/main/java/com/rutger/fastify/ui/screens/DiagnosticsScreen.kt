package com.rutger.fastify.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rutger.fastify.container
import com.rutger.fastify.diag.Diagnostics
import com.rutger.fastify.diag.LogShare
import com.rutger.fastify.diag.ProbeResult
import com.rutger.fastify.ui.theme.Green
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateListOf<ProbeResult>() }
    var running by remember { mutableStateOf(false) }

    fun run() {
        if (running) return
        running = true
        results.clear()
        scope.launch {
            try {
                Diagnostics(c).run { results += it }
            } finally {
                running = false
            }
        }
    }
    LaunchedEffect(Unit) { run() }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Diagnostics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (running) CircularProgressIndicator(Modifier.padding(16.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    LogShare.share(context, results.joinToString("\n") { "${if (it.ok) "OK  " else "FAIL"} ${it.name} (${it.ms} ms): ${it.detail}" })
                }
            }) { Text("Share logs") }
            OutlinedButton(onClick = ::run, enabled = !running) { Text("Run again") }
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(results) { r ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            (if (r.ok) "✓ " else "✗ ") + r.name + "  ·  ${r.ms} ms",
                            color = if (r.ok) Green else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(r.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
