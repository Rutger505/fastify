package com.rutger.fastify.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import com.rutger.fastify.core.RemoteCommand
import com.rutger.fastify.core.RemoteTrack

/**
 * The session's player (notification, lock screen, Bluetooth, the app's controller): while a
 * Jam guest plays the Jam here, skips and seeks go to the Jam ([JamController.redirect]) and
 * come back as Connect commands on the raw player. Play/queue add: [PlayerConnection].
 */
class JamAwarePlayer(player: Player, private val jam: JamController) : ForwardingPlayer(player) {
    override fun seekToNext() { if (!jam.redirect(RemoteCommand.SkipNext)) super.seekToNext() }
    override fun seekToNextMediaItem() { if (!jam.redirect(RemoteCommand.SkipNext)) super.seekToNextMediaItem() }
    override fun seekToPrevious() { if (!jam.redirect(RemoteCommand.SkipPrev)) super.seekToPrevious() }
    override fun seekToPreviousMediaItem() { if (!jam.redirect(RemoteCommand.SkipPrev)) super.seekToPreviousMediaItem() }

    override fun seekTo(positionMs: Long) {
        if (!jam.redirect(RemoteCommand.SeekTo(positionMs.coerceAtLeast(0).toULong()))) super.seekTo(positionMs)
    }

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
        if (cmd == null || !jam.redirect(cmd)) super.seekTo(mediaItemIndex, positionMs)
    }
}
