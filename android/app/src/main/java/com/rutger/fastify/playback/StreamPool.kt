package com.rutger.fastify.playback

import com.rutger.fastify.core.CoreException
import com.rutger.fastify.core.FastifyCore
import com.rutger.fastify.data.Unplayable
import com.rutger.fastify.core.TrackStream

/**
 * ExoPlayer reopens the data source on every seek. Keep recently opened streams so a seek
 * reuses the same download instead of starting a new one.
 */
class StreamPool(private val unplayable: Unplayable, private val core: () -> FastifyCore) {
    private val streams = object : LinkedHashMap<String, TrackStream>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackStream>): Boolean {
            val evict = size > MAX_OPEN
            if (evict) eldest.value.close()
            return evict
        }
    }

    /** Blocking: may hit the network. Call from a loader thread. */
    fun get(trackId: String): TrackStream {
        synchronized(this) { streams[trackId]?.let { return it } }
        if (trackId in unplayable) throw Unplayable.TrackException(trackId, "unplayable: no Ogg Vorbis file for $trackId (known, not retried)")
        val opened = try {
            core().openTrack(trackId)
        } catch (e: CoreException.Unplayable) {
            unplayable.mark(trackId, e.message)
            throw Unplayable.TrackException(trackId, e.message ?: "unplayable").apply { initCause(e) }
        }
        synchronized(this) {
            streams[trackId]?.let {
                opened.close()
                return it
            }
            streams[trackId] = opened
            return opened
        }
    }

    @Synchronized
    fun clear() {
        streams.values.forEach { it.close() }
        streams.clear()
    }

    private companion object {
        const val MAX_OPEN = 3
    }
}
