package com.rutger.fastify

import android.app.Application
import com.rutger.fastify.diag.AppLog
import com.rutger.fastify.auth.AuthManager
import com.rutger.fastify.core.FastifyCore
import com.rutger.fastify.data.Downloads
import com.rutger.fastify.data.LibraryRepository
import com.rutger.fastify.data.SnapshotStore
import com.rutger.fastify.data.PlayHistory
import com.rutger.fastify.playback.PlayerConnection
import com.rutger.fastify.playback.Prefetcher
import com.rutger.fastify.playback.QueueStore
import com.rutger.fastify.playback.StreamPool
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Manual DI: every long-lived object, created lazily so app start does as little as possible. */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Rust core. Loading the native library happens on first use, off the main thread. */
    val core: FastifyCore by lazy {
        FastifyCore(app.filesDir.absolutePath, app.cacheDir.absolutePath, AUDIO_CACHE_BYTES.toULong())
    }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val auth by lazy { AuthManager(app, { http }, { core }) }
    val history by lazy { PlayHistory(File(app.filesDir, "history.json")) }
    val snapshots by lazy { SnapshotStore(File(app.filesDir, "snapshots")) }
    val library by lazy { LibraryRepository({ core }, snapshots, history) }
    val queueStore by lazy { QueueStore(File(app.filesDir, "queue.json")) }
    val streams by lazy { StreamPool { core } }
    val prefetcher by lazy { Prefetcher(scope) { core } }
    val player by lazy { PlayerConnection(app) }
    val downloads by lazy { Downloads(app, scope, { core }, library) }

    private val sessionPrefs by lazy { app.getSharedPreferences("session", android.content.Context.MODE_PRIVATE) }
    private val _username by lazy { MutableStateFlow(sessionPrefs.getString("username", null)) }
    /** Canonical Spotify username (known after the first connect), to tell own playlists apart. */
    val username: StateFlow<String?> get() = _username

    fun forgetUser() {
        sessionPrefs.edit().clear().apply()
        _username.value = null
    }

    /** Connect the Spotify session in the background while the UI draws from cache. */
    fun warmUp() {
        scope.launch(Dispatchers.IO) {
            if (!auth.isLoggedIn) return@launch
            downloads.start()
            runCatching { core.connect() }
                .onSuccess {
                    AppLog.i("Core", "session ready for $it")
                    _username.value = it
                    sessionPrefs.edit().putString("username", it).apply()
                }
                .onFailure { AppLog.w("Core", "warm-up connect failed", it) }
        }
    }

    companion object {
        const val TAG = "Fastify"
        const val AUDIO_CACHE_BYTES = 2L * 1024 * 1024 * 1024
    }
}
