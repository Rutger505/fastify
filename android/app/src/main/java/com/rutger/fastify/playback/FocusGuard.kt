package com.rutger.fastify.playback

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import com.rutger.fastify.core.FastifyCore
import com.rutger.fastify.diag.AppLog

/**
 * Resumes after a permanent audio focus loss when the app that took focus never plays anything.
 * The official Spotify app does this when Android starts it in the background: it grabs focus,
 * stays silent, and Fastify would stay paused. A real takeover (any app playing audio within
 * [WINDOW_MS], a call, a recording) or any play/pause by the user keeps the pause.
 * When the phone's own Spotify app (Connect device named [Build.MODEL]) just connected and Fastify
 * is still the active Connect device, resume right away instead of after [WINDOW_MS]: a real
 * takeover by that app would have made it the active device (Android fades Fastify out for ~2 s
 * before the loss arrives, so the cluster update is in by then).
 * Max [MAX_RESUMES] auto-resumes per [RESUME_PERIOD_MS] so a focus fight can't loop.
 */
class FocusGuard(
    context: Context,
    private val player: Player,
    private val core: () -> FastifyCore,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var armed = false
    private val resumes = ArrayDeque<Long>()

    fun start() {
        player.addListener(listener)
        audio.registerAudioPlaybackCallback(playbackCallback, main)
    }

    fun stop() {
        disarm(null)
        player.removeListener(listener)
        audio.unregisterAudioPlaybackCallback(playbackCallback)
    }

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) arm()
            else disarm("play state changed (reason $reason)")
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            if (armed && configs.isNotEmpty()) disarm("another app plays audio: real takeover")
        }
    }

    private fun arm() {
        armed = true
        main.removeCallbacks(check)
        val woke = runCatching { core().connectWokeWhileActive(Build.MODEL, WOKE_WINDOW_MS.toULong()) }
            .onFailure { AppLog.w(TAG, "focus guard: Connect check failed: $it") }
            .getOrDefault(false)
        if (woke) {
            AppLog.i(TAG, "focus guard: Spotify app on this phone (${Build.MODEL}) just connected, we're still active: resume now unless another app plays audio")
            main.post(check)
        } else {
            main.postDelayed(check, WINDOW_MS)
            AppLog.i(TAG, "focus guard: resume in ${WINDOW_MS / 1000}s unless another app plays audio")
        }
    }

    private fun disarm(why: String?) {
        if (!armed) return
        armed = false
        main.removeCallbacks(check)
        if (why != null) AppLog.i(TAG, "focus guard: stay paused, $why")
    }

    private val check = Runnable {
        if (!armed) return@Runnable
        armed = false
        val blocker = when {
            player.playWhenReady -> "already playing"
            audio.activePlaybackConfigurations.isNotEmpty() -> "another app plays audio"
            audio.mode != AudioManager.MODE_NORMAL -> "audio mode ${audio.mode} (call)"
            audio.activeRecordingConfigurations.isNotEmpty() -> "something records audio"
            else -> null
        }
        if (blocker != null) {
            AppLog.i(TAG, "focus guard: stay paused, $blocker")
            return@Runnable
        }
        val now = System.currentTimeMillis()
        while (resumes.isNotEmpty() && now - resumes.first() > RESUME_PERIOD_MS) resumes.removeFirst()
        if (resumes.size >= MAX_RESUMES) {
            AppLog.w(TAG, "focus guard: stay paused, already auto-resumed ${resumes.size}x in ${RESUME_PERIOD_MS / 60_000} min")
            return@Runnable
        }
        resumes.addLast(now)
        AppLog.w(TAG, "AUTO-RESUME: focus was taken but no app plays audio")
        player.play()
    }

    private companion object {
        const val TAG = "Playback"
        const val WINDOW_MS = 4_000L
        /** Loss came 2.3 s after the Spotify app connected (system fade-out in between). */
        const val WOKE_WINDOW_MS = 10_000L
        const val MAX_RESUMES = 3
        const val RESUME_PERIOD_MS = 10 * 60_000L
    }
}
