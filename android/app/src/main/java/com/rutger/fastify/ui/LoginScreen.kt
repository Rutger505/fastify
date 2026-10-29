package com.rutger.fastify.ui

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rutger.fastify.container
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Fastify ⚡", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Spotify, but it starts before you blink.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))
            if (busy) {
                CircularProgressIndicator()
            } else {
                Button(onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            context.container.auth.login { uri ->
                                CustomTabsIntent.Builder().build().launchUrl(context, uri)
                            }
                            context.container.player.connect()
                            onLoggedIn()
                        } catch (e: Exception) {
                            com.rutger.fastify.diag.AppLog.e("Login", "login failed", e)
                            error = "${e.javaClass.simpleName}: ${e.message}"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Log in with Spotify") }
            }
            error?.let {
                Spacer(Modifier.height(16.dp))
                com.rutger.fastify.ui.screens.ErrorCard("Login failed", it)
            }
        }
    }
}
