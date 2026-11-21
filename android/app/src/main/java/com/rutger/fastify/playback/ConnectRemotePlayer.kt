package com.rutger.fastify.playback

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.rutger.fastify.diag.AppLog
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val TAG = "Session"

/**
 * The session's player while [ConnectRemote] controls another Spotify Connect device: a remote
 * device for Android (volume keys, system volume panel), and notification, lock screen and
 * Bluetooth buttons control that device. State comes from [ConnectRemote.ui]; the queue is
 * previous + current + next only (enough for the buttons). Main thread, like the ExoPlayer.
 */
@UnstableApi
class ConnectRemotePlayer(
    private val remote: ConnectRemote,
    /** Controller calling now, for the log. */
    private val caller: () -> String,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** Queue index (in [ConnectRemote.ui]) of playlist item 0. */
    private var base = 0

    init {
        scope.launch {
            combine(remote.ui, remote.volumes, remote.state, remote.target) { _, _, _, _ -> }
                .collect { invalidateState() }
        }
    }

    private fun steps(): Int {
        val id = remote.target.value ?: return 0
        return remote.state.value?.devices?.find { it.id == id }?.volumeSteps?.toInt() ?: DEFAULT_STEPS
    }

    /** Device volume in steps (0..[steps]). */
    private fun volumeSteps(steps: Int): Int {
        val v = remote.target.value?.let(remote::volume) ?: return 0
        return (v.toFloat() * steps / 65535).roundToInt()
    }

    override fun getState(): State {
        val ui = remote.ui.value
        val steps = steps()
        val playlist = mutableListOf<MediaItemData>()
        var current = 0
        if (ui?.current != null) {
            val from = (ui.index - 1).coerceAtLeast(0)
            val to = (ui.index + 1).coerceAtMost(ui.queue.lastIndex)
            base = from
            for (i in from..to) {
                val t = if (i == ui.index) ui.current else ui.queue[i]
                val durationMs = if (i == ui.index && ui.durationMs > 0) ui.durationMs else t.durationMs
                playlist += MediaItemData.Builder("q$i:${t.id}")
                    .setMediaItem(t.toMediaItem(uid = "q$i"))
                    .setDurationUs(if (durationMs > 0) durationMs * 1000 else C.TIME_UNSET)
                    .setIsSeekable(true)
                    .build()
            }
            current = ui.index - from
        }
        val commands = Player.Commands.Builder().addAll(
            COMMAND_PLAY_PAUSE, COMMAND_STOP, COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE,
            COMMAND_GET_METADATA, COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, COMMAND_SEEK_TO_MEDIA_ITEM,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_NEXT,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SET_SHUFFLE_MODE, COMMAND_SET_REPEAT_MODE, COMMAND_GET_DEVICE_VOLUME, COMMAND_RELEASE,
        )
        if (steps > 0) commands.addAll(COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
        return State.Builder()
            .setAvailableCommands(commands.build())
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(current)
            .setContentPositionMs { remote.position() }
            .setPlayWhenReady(ui?.isPlaying == true, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(if (playlist.isEmpty()) STATE_IDLE else STATE_READY)
            .setShuffleModeEnabled(ui?.shuffle == true)
            .setRepeatMode(ui?.repeatMode ?: REPEAT_MODE_OFF)
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(steps).build())
            .setDeviceVolume(volumeSteps(steps))
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        AppLog.i(TAG, "${if (playWhenReady) "play" else "pause"} on ${remote.ui.value?.remoteDevice} by ${caller()}")
        if (playWhenReady) remote.resume() else remote.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> = handleSetPlayWhenReady(false)

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val index = base + mediaItemIndex
        val ms = positionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        val what = when {
            seekCommand == COMMAND_SEEK_TO_NEXT || seekCommand == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> "next".also { remote.next() }
            // Spotify's own rule (restart the track past 3 s), like the device's buttons.
            seekCommand == COMMAND_SEEK_TO_PREVIOUS -> "previous".also { remote.previous() }
            seekCommand == COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM || index == remote.ui.value?.index ->
                "seek to ${ms / 1000}s".also { remote.seekTo(ms) }
            else -> "queue index $index".also { remote.playQueueIndex(index) }
        }
        AppLog.i(TAG, "$what on ${remote.ui.value?.remoteDevice} by ${caller()}")
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        remote.setShuffle(shuffleModeEnabled)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        remote.setRepeatMode(repeatMode)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> = setSteps(deviceVolume)
    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> = setSteps(volumeSteps(steps()) + 1)
    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> = setSteps(volumeSteps(steps()) - 1)

    /** Optimistic: [ConnectRemote.volumes] holds it, so the next key press counts from here. */
    private fun setSteps(volume: Int): ListenableFuture<*> {
        val id = remote.target.value
        val steps = steps()
        if (id != null && steps > 0) {
            val v = volume.coerceIn(0, steps)
            AppLog.i(TAG, "volume $v/$steps on ${remote.ui.value?.remoteDevice} by ${caller()}")
            remote.setVolume(id, (v * 65535L / steps).toInt())
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        /** Devices that don't report their steps (Spotify's default). */
        const val DEFAULT_STEPS = 16
    }
}
