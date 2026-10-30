package com.rutger.fastify.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rutger.fastify.container
import com.rutger.fastify.ui.theme.FastifyTheme

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

    override fun onStart() {
        super.onStart()
        // Binding the session early lets the service restore and prepare the last queue right away.
        if (container.auth.isLoggedIn) container.player.connect()
    }

    override fun onStop() {
        container.player.disconnect()
        super.onStop()
    }
}
