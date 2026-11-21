package com.rutger.fastify.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.rutger.fastify.diag.AppLog

/**
 * Why playback starts and stops, in the app log (Share logs): every play/pause with its reason
 * (user via which controller, audio focus, noisy output, Connect), buffering, track changes,
 * audio focus holders (other apps' players), output devices (Bluetooth) and phone calls.
 * Connect takeovers are logged by the core (core/connect) in the same file.
 */
class PlaybackLog(context: Context, private val player: Player) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    /** Audio players last logged / last seen (they come and go for a few ms on every track). */
    private var loggedPlayers = ""
    private var seenPlayers = ""
    private val logPlayers = Runnable {
        if (seenPlayers != loggedPlayers) {
            loggedPlayers = seenPlayers
            AppLog.i(TAG, "audio players (all apps): ${seenPlayers.ifEmpty { "none" }}")
        }
    }
    private var suppressedAt = 0L
    private var bufferingAt = 0L

    fun start() {
        player.addListener(listener)
        audio.registerAudioPlaybackCallback(playbackCallback, main)
        audio.registerAudioDeviceCallback(deviceCallback, main)
        audio.addOnModeChangedListener({ main.post(it) }, modeListener)
        AppLog.i(TAG, "service start, ${state()}")
    }

    fun stop() {
        AppLog.i(TAG, "service stop, ${state()}")
        player.removeListener(listener)
        audio.unregisterAudioPlaybackCallback(playbackCallback)
        audio.unregisterAudioDeviceCallback(deviceCallback)
        audio.removeOnModeChangedListener(modeListener)
        main.removeCallbacks(logPlayers)
    }

    private fun state(): String {
        val track = player.currentMediaItem?.mediaId ?: "-"
        return "track $track @${player.currentPosition / 1000}s, playWhenReady ${player.playWhenReady}, " +
            "state ${stateName(player.playbackState)}, items ${player.mediaItemCount}"
    }

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val why = when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "user/app/Connect"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO FOCUS LOST (other app took audio)"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "BECOMING NOISY (headphones/Bluetooth disconnected)"
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end of item"
                Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "suppressed too long"
                else -> "reason $reason"
            }
            if (playWhenReady) AppLog.i(TAG, "PLAY: $why; ${state()}")
            else AppLog.w(TAG, "PAUSE: $why; ${state()}")
        }

        override fun onPlaybackSuppressionReasonChanged(reason: Int) {
            val why = when (reason) {
                Player.PLAYBACK_SUPPRESSION_REASON_NONE -> "none"
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> "transient audio focus loss"
                Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT -> "unsuitable audio output"
                else -> "reason $reason"
            }
            // Short ones (notification sounds ducking us) only in logcat.
            if (reason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                suppressedAt = System.currentTimeMillis()
                AppLog.d(TAG, "suppression: $why")
                return
            }
            val ms = System.currentTimeMillis() - suppressedAt
            if (ms >= SUPPRESSED_LONG_MS) AppLog.i(TAG, "suppressed for $ms ms (ended)") else AppLog.d(TAG, "suppression ended after $ms ms")
        }

        /** Buffering -> ready within a second (every track start, seek) only in logcat. */
        override fun onPlaybackStateChanged(playbackState: Int) {
            val now = System.currentTimeMillis()
            when (playbackState) {
                Player.STATE_BUFFERING -> { bufferingAt = now; AppLog.d(TAG, "state buffering: ${state()}") }
                Player.STATE_READY -> {
                    val ms = now - bufferingAt
                    if (bufferingAt > 0 && ms >= LONG_MS && player.playWhenReady) AppLog.i(TAG, "ready after buffering $ms ms: ${state()}")
                    else AppLog.d(TAG, "state ready: ${state()}")
                    bufferingAt = 0
                }
                else -> AppLog.i(TAG, "state ${stateName(playbackState)}: ${state()}")
            }
        }

        /** Seeks and jumps (also the player's own, e.g. after an Ogg seek) with where it came from. */
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) return
            val why = when (reason) {
                Player.DISCONTINUITY_REASON_SEEK -> "seek"
                Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "seek adjustment"
                Player.DISCONTINUITY_REASON_SKIP -> "skip"
                Player.DISCONTINUITY_REASON_REMOVE -> "remove"
                Player.DISCONTINUITY_REASON_INTERNAL -> "internal"
                Player.DISCONTINUITY_REASON_SILENCE_SKIP -> "silence skip"
                else -> "reason $reason"
            }
            val item = if (old.mediaItemIndex != new.mediaItemIndex) ", item ${old.mediaItemIndex} -> ${new.mediaItemIndex}" else ""
            val line = "position ${old.positionMs} -> ${new.positionMs} ms ($why$item); ${state()}"
            // Track changes have their own line; adjustments follow every seek.
            if (reason == Player.DISCONTINUITY_REASON_SEEK && item.isEmpty()) AppLog.i(TAG, line) else AppLog.d(TAG, line)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val why = when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto"
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek/skip"
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "queue changed"
                else -> "reason $reason"
            }
            AppLog.i(TAG, "track ${mediaItem?.mediaId ?: "-"} ($why)")
        }
    }

    /**
     * Active players of all apps: when another one appears right before a pause, it took focus.
     * Logged once the set is stable for [PLAYERS_STABLE_MS] and differs from the last logged one
     * (ExoPlayer's own come and go on every track change).
     */
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            val players = configs.map { usageName(it.audioAttributes.usage) }.sorted().joinToString()
            if (players == seenPlayers) return
            seenPlayers = players
            AppLog.d(TAG, "audio players (all apps): ${players.ifEmpty { "none" }}")
            main.removeCallbacks(logPlayers)
            main.postDelayed(logPlayers, PLAYERS_STABLE_MS)
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = devices("added", added)
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = devices("removed", removed)
    }

    private fun devices(what: String, list: Array<out AudioDeviceInfo>) {
        val outputs = list.filter { it.isSink && it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        if (outputs.isEmpty()) return
        AppLog.i(TAG, "outputs $what: " + outputs.joinToString { "${it.productName} (type ${it.type})" })
    }

    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        val name = when (mode) {
            AudioManager.MODE_NORMAL -> "normal"
            AudioManager.MODE_RINGTONE -> "ringtone"
            AudioManager.MODE_IN_CALL -> "in call"
            AudioManager.MODE_IN_COMMUNICATION -> "in communication (VoIP)"
            AudioManager.MODE_CALL_SCREENING -> "call screening"
            else -> "mode $mode"
        }
        AppLog.i(TAG, "audio mode: $name")
    }

    private companion object {
        const val TAG = "Playback"
        /** Shorter buffering / suppression (notification sounds ducking us): logcat only. */
        const val LONG_MS = 1_000L
        const val SUPPRESSED_LONG_MS = 10_000L
        const val PLAYERS_STABLE_MS = 2_000L

        fun stateName(state: Int) = when (state) {
            Player.STATE_IDLE -> "idle"
            Player.STATE_BUFFERING -> "buffering"
            Player.STATE_READY -> "ready"
            Player.STATE_ENDED -> "ended"
            else -> "$state"
        }

        fun usageName(usage: Int) = when (usage) {
            android.media.AudioAttributes.USAGE_MEDIA -> "media"
            android.media.AudioAttributes.USAGE_GAME -> "game"
            android.media.AudioAttributes.USAGE_ASSISTANT -> "assistant"
            android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "navigation"
            android.media.AudioAttributes.USAGE_NOTIFICATION -> "notification"
            android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "ringtone"
            android.media.AudioAttributes.USAGE_ALARM -> "alarm"
            android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION -> "call"
            android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "ui sound"
            else -> "usage $usage"
        }
    }
}
