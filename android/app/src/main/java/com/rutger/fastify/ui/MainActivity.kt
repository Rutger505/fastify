package com.rutger.fastify.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rutger.fastify.BuildConfig
import com.rutger.fastify.container
import com.rutger.fastify.ui.screens.toast
import com.rutger.fastify.ui.theme.FastifyTheme

private val JAM_LINK = Regex("""socialsession|spotify(\.app)?\.link/""")

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = container
        // Without this the media notification (and lock-screen controls) never shows. Also covers
        // installs that were logged in before the permission was requested at login.
        if (c.auth.isLoggedIn) requestNotificationPermission()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            FastifyTheme {
                val loggedIn by c.auth.loggedIn.collectAsStateWithLifecycle()
                if (loggedIn) {
                    FastifyNavHost()
                } else {
                    LoginScreen(onLoggedIn = ::requestNotificationPermission)
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Jam invite opened (open.spotify.com/socialsession/...) or shared as text. */
    private fun handleIntent(intent: Intent?) {
        // Debug builds: `am start -n com.rutger.fastify/.ui.MainActivity --ez open_jam true` opens the Jam sheet
        // without playing anything (the player's Jam button needs a track, which takes over Rutger's playback).
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("open_jam", false) == true) return container.jam.openSheet()
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        if (intent?.data?.scheme == "fastify") return
        if (!container.auth.isLoggedIn) return toast("Log in first, then open the Jam invite again")
        if (!JAM_LINK.containsMatchIn(text)) return toast("Not a Spotify Jam invite: $text")
        container.jam.openInvite(text)
    }

    override fun onStart() {
        super.onStart()
        container.foreground.value = true
        container.updater.onForeground()
        // Binding the session early lets the service restore and prepare the last queue right away.
        container.remote.start()
        if (container.auth.isLoggedIn) {
            container.player.connect()
            container.jam.startWatching()
        }
    }

    override fun onStop() {
        container.player.disconnect()
        container.remote.stop()
        container.foreground.value = false
        super.onStop()
    }
}
