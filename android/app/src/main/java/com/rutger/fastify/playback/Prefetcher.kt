package com.rutger.fastify.playback

import android.util.Log
import com.rutger.fastify.core.CoreException
import com.rutger.fastify.core.FastifyCore
import com.rutger.fastify.data.Unplayable
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Downloads upcoming tracks fully into the cache (file + key) so they start with no network. */
class Prefetcher(private val scope: CoroutineScope, private val unplayable: Unplayable, private val core: () -> FastifyCore) {
    private val inFlight = Collections.synchronizedSet(HashSet<String>())
    private val gate = Semaphore(2)

    fun prefetch(trackIds: List<String>) {
        for (id in trackIds) {
            if (id in unplayable || !inFlight.add(id)) continue
            scope.launch(Dispatchers.IO) {
                try {
                    gate.withPermit {
                        if (!core().isTrackCached(id)) core().prefetch(id)
                    }
                } catch (e: CoreException.Unplayable) {
                    unplayable.mark(id, e.message)
                } catch (e: Exception) {
                    Log.w("FastifyPrefetch", "prefetch $id failed: ${e.message}")
                } finally {
                    inFlight.remove(id)
                }
            }
        }
    }
}
