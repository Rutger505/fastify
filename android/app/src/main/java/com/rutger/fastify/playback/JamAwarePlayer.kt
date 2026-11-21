package com.rutger.fastify.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import com.rutger.fastify.core.RemoteCommand
import com.rutger.fastify.core.RemoteTrack
import com.rutger.fastify.diag.AppLog

private const val TAG = "Session"

/**
 * The session's player (notification, lock screen, Bluetooth, the app's controller): while a
 * Jam guest plays the Jam here, skips and seeks go to the Jam ([JamController.redirect]) and
 * come back as Connect commands on the raw player. Play/queue add: [PlayerConnection].
 */
class JamAwarePlayer(
    player: Player,
    private val jam: JamController,
    /** Package of the controller calling now, for the log (who paused?). */
    private val caller: () -> String,
) : ForwardingPlayer(player) {
    override fun play() { AppLog.i(TAG, "play by ${caller()}"); super.play() }
    override fun pause() { AppLog.i(TAG, "pause by ${caller()}"); super.pause() }
    override fun stop() { AppLog.i(TAG, "stop by ${caller()}"); super.stop() }
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        AppLog.i(TAG, "${if (playWhenReady) "play" else "pause"} by ${caller()}")
        super.setPlayWhenReady(playWhenReady)
    }


    override fun seekToNext() { if (!redirect("skip next", RemoteCommand.SkipNext)) super.seekToNext() }
    override fun seekToNextMediaItem() { if (!redirect("skip next", RemoteCommand.SkipNext)) super.seekToNextMediaItem() }
    override fun seekToPrevious() { if (!redirect("skip prev", RemoteCommand.SkipPrev)) super.seekToPrevious() }
    override fun seekToPreviousMediaItem() { if (!redirect("skip prev", RemoteCommand.SkipPrev)) super.seekToPreviousMediaItem() }

    override fun seekTo(positionMs: Long) {
        if (!redirect("seek to ${positionMs / 1000}s", RemoteCommand.SeekTo(positionMs.coerceAtLeast(0).toULong()))) super.seekTo(positionMs)
    }

    /** Logs the action with its source; true: the Jam host does it (comes back via Connect). */
    private fun redirect(what: String, cmd: RemoteCommand): Boolean =
        jam.redirect(cmd).also { AppLog.i(TAG, "$what by ${caller()}" + if (it) " (sent to the Jam)" else "") }

    /** Queue tap: the Jam knows our items by the uids ConnectTarget publishes. */
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        val sameTrack = mediaItemIndex == currentMediaItemIndex
        val cmd = when {
            mediaItemIndex !in 0 until mediaItemCount -> null
            sameTrack -> RemoteCommand.SeekTo(positionMs.coerceAtLeast(0).toULong())
            else -> getMediaItemAt(mediaItemIndex).let { item ->
                val provider = if (item.isQueued) "queue" else if (item.isAutoplay) "autoplay" else "context"
                RemoteCommand.SkipTo(RemoteTrack("spotify:track:${item.mediaId}", item.uid, provider))
            }
        }
        if (cmd == null || !redirect("jump to item $mediaItemIndex", cmd)) super.seekTo(mediaItemIndex, positionMs)
    }
}
