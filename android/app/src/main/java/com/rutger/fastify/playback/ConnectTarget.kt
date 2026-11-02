package com.rutger.fastify.playback

import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.rutger.fastify.core.ConnectRequest
import com.rutger.fastify.core.FastifyCore
import com.rutger.fastify.core.IncomingRequest
import com.rutger.fastify.core.LocalPlayer
import com.rutger.fastify.core.RemoteTrack
import com.rutger.fastify.data.toTrack
import com.rutger.fastify.diag.AppLog
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * This phone as a real Spotify Connect device, while the playback service runs: other devices
 * (desktop app) list it, transfer playback here and control it. Their commands arrive through
 * the Rust core and run on [player]; every local change is published back so they show the
 * phone's live state. Playing here makes the phone the account's active device (the other one
 * stops); when another device takes over, the phone pauses.
 *
 * Queue items are identified by their [uid] extra (SkipNext/SetQueue refer to them).
 */
@UnstableApi
class ConnectTarget(
    context: Context,
    private val player: ExoPlayer,
    private val core: () -> FastifyCore,
    private val playOrder: () -> List<Int>,
    /** What the queue was started from (spotify:playlist:...), and setting it for a new queue. */
    private val contextUri: () -> String?,
    private val setContext: (String?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val name = "Fastify (${Build.MODEL})"
    /**
     * Calls into the core, off the main thread but in order: the state must be in before a
     * reply or activation, which PUT it right away (else the other device sees the old track).
     */
    private val ops = Channel<Op>(Channel.UNLIMITED)
    private var debounce: Job? = null
    /** Last volume another device set (0..65535) and the stream index it became. */
    private var remoteVolume: Pair<Int, UInt>? = null

    private sealed interface Op {
        data class SetPlayer(val player: LocalPlayer) : Op
        data class Reply(val id: ULong, val ok: Boolean) : Op
        data object Activate : Op
        data object Deactivate : Op
    }

    fun start() {
        player.addListener(listener)
        scope.launch(Dispatchers.IO) {
            for (op in ops) {
                runCatching {
                    when (op) {
                        is Op.SetPlayer -> core().connectSetPlayer(op.player)
                        is Op.Reply -> core().connectReply(op.id, op.ok)
                        Op.Activate -> core().connectActivate()
                        Op.Deactivate -> core().connectDeactivate()
                    }
                }.onFailure { AppLog.w(TAG, "core $op", it) }
            }
        }
        publishNow()
        scope.launch {
            var failures = 0
            while (isActive) {
                runCatching { withContext(Dispatchers.IO) { core().connectNextRequest() } }
                    .onSuccess { req ->
                        failures = 0
                        req?.let(::handle)
                    }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        AppLog.w(TAG, "requests", e)
                        delay(2_000L shl failures.coerceAtMost(4))
                        failures++
                    }
            }
        }
    }

    fun stop() {
        player.removeListener(listener)
        scope.cancel()
        // Back to a hidden observer: the device can't be reached without the service.
        Thread { runCatching { core().connectSetPlayer(null) } }.start()
    }

    private fun handle(req: IncomingRequest) {
        val what = req.command.javaClass.simpleName
        val result = runCatching { execute(req.command) }
        result.onFailure { AppLog.w(TAG, "$what from ${req.fromDeviceId} failed", it) }
            .onSuccess { AppLog.i(TAG, "$what from ${req.fromDeviceId}") }
        publishNow()
        if (!req.isEvent) ops.trySend(Op.Reply(req.id, result.isSuccess))
    }

    private val IncomingRequest.isEvent get() = command is ConnectRequest.Volume ||
        command is ConnectRequest.Deactivated || command is ConnectRequest.Metadata

    private fun execute(cmd: ConnectRequest) {
        when (cmd) {
            is ConnectRequest.Load -> load(cmd)
            ConnectRequest.Pause, ConnectRequest.Deactivated -> player.pause()
            ConnectRequest.Resume -> {
                check(player.mediaItemCount > 0) { "nothing loaded" }
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(player.currentMediaItemIndex, 0)
                player.play()
            }
            is ConnectRequest.SkipNext -> skipNext(cmd.track)
            ConnectRequest.SkipPrev -> player.seekToPrevious()
            is ConnectRequest.SeekTo -> player.seekTo(cmd.positionMs.toLong())
            is ConnectRequest.SetOptions -> {
                cmd.shuffle?.let { player.shuffleModeEnabled = it }
                setRepeat(cmd.repeatContext, cmd.repeatTrack)
            }
            is ConnectRequest.AddToQueue -> {
                val item = cmd.track.toTrack().toMediaItem(queued = true)
                if (player.mediaItemCount == 0) {
                    player.setMediaItem(item)
                    player.prepare()
                    return
                }
                var at = player.currentMediaItemIndex + 1
                while (at < player.mediaItemCount && player.getMediaItemAt(at).isQueued) at++
                player.addMediaItem(at, item)
            }
            is ConnectRequest.SetQueue -> setQueue(cmd.next.map { it.uid })
            is ConnectRequest.Metadata -> fillStubs(cmd)
            is ConnectRequest.Volume -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val index = (cmd.volume.toFloat() / 65535 * max).roundToInt()
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
                remoteVolume = index to cmd.volume
            }
        }
    }

    /**
     * The sender saw only the published window (MAX_NEXT upcoming): drop window items missing
     * from [wanted], keep everything past it, and follow its order (not while shuffling: the
     * window is the shuffle order there, not the item order).
     */
    private fun setQueue(wanted: List<String>) {
        val order = playOrder()
        val window = order.drop(order.indexOf(player.currentMediaItemIndex) + 1).take(MAX_NEXT)
        val keep = wanted.toSet()
        window.filter { uid(it) !in keep }.sortedDescending().forEach(player::removeMediaItem)
        if (player.shuffleModeEnabled) return
        var target = player.currentMediaItemIndex + 1
        for (u in wanted) {
            val from = (target until player.mediaItemCount).firstOrNull { uid(it) == u } ?: continue
            if (from != target) player.moveMediaItem(from, target)
            target++
        }
    }

    private fun isStub(index: Int) = player.getMediaItemAt(index).mediaMetadata.title.isNullOrEmpty()

    /** Metadata for the stubs of a big Load: replace them in runs (one timeline change each). */
    private fun fillStubs(cmd: ConnectRequest.Metadata) {
        val byId = cmd.tracks.associateBy { it.id }
        var i = 0
        while (i < player.mediaItemCount) {
            val run = ArrayList<MediaItem>()
            var j = i
            while (j < player.mediaItemCount && isStub(j)) {
                val old = player.getMediaItemAt(j)
                val info = byId[old.mediaId] ?: break
                run += info.toTrack().toMediaItem(old.isAutoplay, old.isQueued, old.uid)
                j++
            }
            if (run.isEmpty()) i++ else { player.replaceMediaItems(i, j, run); i = j }
        }
        val missing = cmd.missing.toSet()
        (0 until player.mediaItemCount)
            .filter { it != player.currentMediaItemIndex && isStub(it) && player.getMediaItemAt(it).mediaId in missing }
            .sortedDescending()
            .forEach(player::removeMediaItem)
    }

    private fun load(cmd: ConnectRequest.Load) {
        val items = cmd.tracks.map { it.toTrack().toMediaItem() }
        check(items.isNotEmpty()) { "no tracks" }
        val shuffle = cmd.shuffle ?: player.shuffleModeEnabled
        val start = when {
            cmd.startIndex >= 0 -> cmd.startIndex.coerceAtMost(items.lastIndex)
            // Not a stub (big context, metadata still coming).
            shuffle -> cmd.tracks.indices.filter { cmd.tracks[it].title.isNotEmpty() }.randomOrNull() ?: items.indices.random()
            else -> 0
        }
        setContext(cmd.contextUri)
        // Off and on again: the service reshuffles with the start track first.
        player.shuffleModeEnabled = false
        player.setMediaItems(items, start, cmd.positionMs.toLong())
        if (cmd.queued.isNotEmpty()) {
            player.addMediaItems(start + 1, cmd.queued.map { it.toTrack().toMediaItem(queued = true) })
        }
        player.shuffleModeEnabled = shuffle
        setRepeat(cmd.repeatContext, cmd.repeatTrack)
        player.prepare()
        player.playWhenReady = !cmd.paused
    }

    /** Spotify: repeat track = context + track flags. null keeps the current flag. */
    private fun setRepeat(context: Boolean?, track: Boolean?) {
        if (context == null && track == null) return
        val ctx = context ?: (player.repeatMode != Player.REPEAT_MODE_OFF)
        val one = track ?: (player.repeatMode == Player.REPEAT_MODE_ONE)
        player.repeatMode = when {
            one -> Player.REPEAT_MODE_ONE
            ctx -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun skipNext(track: RemoteTrack?) {
        if (track != null) {
            val order = playOrder()
            val upcoming = order.drop(order.indexOf(player.currentMediaItemIndex) + 1)
            val id = track.uri.removePrefix("spotify:track:")
            val target = upcoming.firstOrNull { uid(it) == track.uid }
                ?: upcoming.firstOrNull { player.getMediaItemAt(it).mediaId == id }
            if (target != null) {
                player.seekTo(target, 0)
                player.play()
                return
            }
        }
        player.seekToNext()
    }

    private fun uid(index: Int) = player.getMediaItemAt(index).uid

    private fun remoteTrack(index: Int): RemoteTrack {
        val item = player.getMediaItemAt(index)
        val provider = when {
            item.isQueued -> "queue"
            item.isAutoplay -> "autoplay"
            else -> "context"
        }
        return RemoteTrack("spotify:track:${item.mediaId}", uid(index), provider)
    }

    private fun publish() {
        debounce?.cancel()
        debounce = scope.launch {
            delay(PUBLISH_DEBOUNCE_MS)
            publishNow()
        }
    }

    private fun publishNow() {
        debounce?.cancel()
        val count = player.mediaItemCount
        val cur = player.currentMediaItemIndex
        val order = if (count > 0) playOrder() else emptyList()
        val pos = order.indexOf(cur)
        val playing = player.playWhenReady && player.playbackState in setOf(Player.STATE_READY, Player.STATE_BUFFERING)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volumeIndex = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val state = LocalPlayer(
            name = name,
            track = if (count > 0 && cur in 0 until count) remoteTrack(cur) else null,
            isPlaying = playing,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0).toULong(),
            positionAtMs = System.currentTimeMillis().toULong(),
            durationMs = (player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                ?: player.currentMediaItem?.mediaMetadata?.durationMs ?: 0).toULong(),
            shuffle = player.shuffleModeEnabled,
            repeatContext = player.repeatMode != Player.REPEAT_MODE_OFF,
            repeatTrack = player.repeatMode == Player.REPEAT_MODE_ONE,
            contextUri = contextUri(),
            prevTracks = if (pos > 0) order.subList((pos - MAX_PREV).coerceAtLeast(0), pos).map(::remoteTrack) else emptyList(),
            nextTracks = if (pos >= 0) order.drop(pos + 1).take(MAX_NEXT).map(::remoteTrack) else emptyList(),
            // Echo what the other device set while the stream is still at that step, else its
            // slider snaps to the nearest of the phone's ~15 steps.
            volume = remoteVolume?.takeIf { it.first == volumeIndex }?.second
                ?: (volumeIndex * 65535 / max).toUInt(),
        )
        ops.trySend(Op.SetPlayer(state))
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Playing here takes over from whatever device was active (with the playing state).
            if (isPlaying) { publishNow(); ops.trySend(Op.Activate) }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (timeline.isEmpty) { publishNow(); ops.trySend(Op.Deactivate) }
        }

        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    private companion object {
        const val TAG = "ConnectTarget"
        const val PUBLISH_DEBOUNCE_MS = 150L
        const val MAX_PREV = 10
        const val MAX_NEXT = 80
    }
}
