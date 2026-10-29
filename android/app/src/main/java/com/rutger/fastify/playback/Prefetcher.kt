package com.rutger.fastify.playback

import android.util.Log
import com.rutger.fastify.core.FastifyCore
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Downloads upcoming tracks fully into the cache (file + key) so they start with no network. */
class Prefetcher(private val scope: CoroutineScope, private val core: () -> FastifyCore) {
    private val inFlight = Collections.synchronizedSet(HashSet<String>())
    private val gate = Semaphore(2)

    fun prefetch(trackIds: List<String>) {
        for (id in trackIds) {
            if (!inFlight.add(id)) continue
            scope.launch(Dispatchers.IO) {
                try {
                    gate.withPermit {
                        if (!core().isTrackCached(id)) core().prefetch(id)
                    }
                } catch (e: Exception) {
                    Log.w("FastifyPrefetch", "prefetch $id failed: ${e.message}")
                } finally {
                    inFlight.remove(id)
                }
            }
        }
    }
}
