package com.rutger.fastify.data

import android.content.SharedPreferences
import com.rutger.fastify.core.CoreException
import com.rutger.fastify.diag.AppLog
import java.io.FileNotFoundException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks the core can't play (no Ogg Vorbis file, [CoreException.Unplayable]), remembered per
 * track id so playback, prefetch and downloads don't retry them. Entries expire after 30 days in
 * case Spotify adds a file.
 */
class Unplayable(private val prefs: SharedPreferences) {
    private val ids: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap(
        prefs.all.mapNotNull { (k, v) -> (v as? Long)?.takeIf { fresh(it) }?.let { k to it } }.toMap(),
    )

    /** The core's error for the last track playback skipped, for the player UI. */
    private val _skipped = MutableStateFlow<String?>(null)
    val skipped: StateFlow<String?> = _skipped

    operator fun contains(trackId: String): Boolean = ids[trackId]?.let(::fresh) == true

    /** Remembers the track; logs once per track id. */
    fun mark(trackId: String, reason: String?) {
        if (ids.put(trackId, System.currentTimeMillis()) != null) return
        prefs.edit().putLong(trackId, ids[trackId]!!).apply()
        AppLog.i("Unplayable", "$trackId: ${reason ?: "unplayable"}; not retried")
    }

    fun reportSkip(message: String?) {
        _skipped.value = message
    }

    fun clear() {
        ids.clear()
        prefs.edit().clear().apply()
        _skipped.value = null
    }

    private fun fresh(at: Long) = System.currentTimeMillis() - at < TTL_MS

    /** ExoPlayer doesn't retry FileNotFoundException: an unplayable track fails once. */
    class TrackException(val trackId: String, message: String) : FileNotFoundException(message)

    companion object {
        private const val TTL_MS = 30L * 24 * 60 * 60 * 1000

        /** The unplayable cause in [e]'s chain, if any. */
        fun cause(e: Throwable?): Throwable? =
            generateSequence(e) { it.cause }.firstOrNull { it is CoreException.Unplayable || it is TrackException }
    }
}
