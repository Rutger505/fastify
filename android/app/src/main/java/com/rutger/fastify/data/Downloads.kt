package com.rutger.fastify.data

import android.content.Context
import android.content.Intent
import com.rutger.fastify.core.FastifyCore
import com.rutger.fastify.diag.AppLog
import com.rutger.fastify.playback.DownloadService
import com.rutger.fastify.playback.DownloadWorker
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class DownloadIndex(val collections: List<Collection> = emptyList())

data class DownloadProgress(val done: Int, val total: Int, val failed: Int) {
    val complete: Boolean get() = total > 0 && done + failed >= total
}

/**
 * Offline downloads, Spotify style: pin a playlist/album/Liked Songs and every track in it is
 * downloaded outside the LRU audio cache (Rust keeps them in files/downloads, never evicted).
 * Pinned collections stay in sync: new tracks are downloaded, removed ones deleted.
 * Work runs in the app scope. While it runs, [DownloadService] (dataSync foreground service) keeps
 * the process alive, but Android 15+ only lets that start while the app is visible: from the
 * background (process started by boot or a worker) [DownloadWorker] holds the process instead, and
 * the service takes over once the app is opened.
 */
class Downloads(
    private val context: Context,
    private val scope: CoroutineScope,
    private val core: () -> FastifyCore,
    private val library: LibraryRepository,
    private val foreground: StateFlow<Boolean>,
) {
    private val file = File(context.filesDir, "downloads.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _collections = MutableStateFlow(load())
    /** Pinned collections. */
    val collections: StateFlow<List<Collection>> = _collections

    private val _trackIds = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of tracks that are fully downloaded. */
    val trackIds: StateFlow<Set<String>> = _trackIds

    /** Track ids per pinned collection (cache key), from the latest known track list. */
    private val members = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private val failed = MutableStateFlow<Set<String>>(emptySet())
    private val pending = Collections.synchronizedSet(LinkedHashSet<String>())
    private val _active = MutableStateFlow(0)
    /** Tracks queued or downloading. */
    val active: StateFlow<Int> = _active

    private val gate = Semaphore(2)
    private var syncJob: Job? = null

    val progress: StateFlow<Map<String, DownloadProgress>> =
        combine(members, _trackIds, failed) { m, done, bad ->
            m.mapValues { (_, ids) ->
                DownloadProgress(ids.count { it in done }, ids.size, ids.count { it in bad && it !in done })
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        // Opened while downloads run in the background (worker): move them to the service.
        scope.launch {
            foreground.collect { if (it && _active.value > 0) startService() }
        }
    }

    /** Suspends until no download is queued or running. */
    suspend fun awaitIdle() {
        syncJob?.join()
        _active.first { it == 0 }
    }

    fun isPinned(c: Collection) = _collections.value.any { it.cacheKey == c.cacheKey }

    /** Load state and resume unfinished downloads. Only the first call does anything. */
    @Synchronized
    fun start() {
        if (syncJob != null) return
        syncJob = scope.launch(Dispatchers.IO) {
            _trackIds.value = runCatching { core().downloadedTracks().toSet() }
                .onFailure { AppLog.w("Downloads", "listing downloads failed", it) }
                .getOrDefault(emptySet())
            for (c in _collections.value) {
                library.cachedTracks(c)?.let { sync(c, it) }
            }
        }
    }

    fun pin(c: Collection, tracks: List<Track>) {
        if (!isPinned(c)) {
            _collections.update { it + c }
            save()
        }
        sync(c, tracks)
    }

    fun unpin(c: Collection) {
        _collections.update { list -> list.filterNot { it.cacheKey == c.cacheKey } }
        save()
        val removed = members.value[c.cacheKey].orEmpty()
        members.update { it - c.cacheKey }
        deleteUnreferenced(removed)
    }

    /** New track list for a collection (fresh from the network): download/delete the difference. */
    fun sync(c: Collection, tracks: List<Track>) {
        if (!isPinned(c)) return
        val ids = tracks.map { it.id }.distinct()
        val before = members.value[c.cacheKey].orEmpty()
        members.update { it + (c.cacheKey to ids) }
        deleteUnreferenced(before - ids.toSet())
        ids.filter { it !in _trackIds.value && it !in failed.value }.forEach(::enqueue)
    }

    /** Retry tracks that failed (e.g. offline at the time). */
    fun retryFailed() {
        val ids = failed.value
        failed.value = emptySet()
        ids.forEach(::enqueue)
    }

    fun clear() {
        _collections.value = emptyList()
        members.value = emptyMap()
        _trackIds.value = emptySet()
        file.delete()
    }

    private fun enqueue(id: String) {
        if (!pending.add(id)) return
        if (_active.getAndUpdate { it + 1 } == 0) {
            batch.set(Batch(System.currentTimeMillis()))
            AppLog.i("Downloads", "downloads started")
            // The service/worker stays until the queue is idle again.
            startService()
        }
        scope.launch(Dispatchers.IO) {
            try {
                gate.withPermit {
                    // Unpinned while waiting.
                    if (members.value.values.none { id in it }) return@withPermit
                    AppLog.d("Downloads", "download $id")
                    core().download(id)
                    _trackIds.update { it + id }
                    batch.get().ok.incrementAndGet()
                }
            } catch (e: Exception) {
                AppLog.w("Downloads", "download $id failed: ${e.message}", repeated = true)
                batch.get().failed.incrementAndGet()
                failed.update { it + id }
            } finally {
                pending.remove(id)
                if (_active.updateAndGet { it - 1 } == 0) {
                    val b = batch.get()
                    AppLog.i("Downloads", "downloads done: ${b.ok} ok, ${b.failed} failed in ${(System.currentTimeMillis() - b.started) / 1000} s")
                }
            }
        }
    }

    private fun deleteUnreferenced(ids: kotlin.collections.Collection<String>) {
        if (ids.isEmpty()) return
        val keep = members.value.values.flatten().toSet()
        val drop = ids.filter { it !in keep }
        scope.launch(Dispatchers.IO) {
            for (id in drop) runCatching { core().removeDownload(id) }
            _trackIds.update { it - drop.toSet() }
        }
    }

    /** Counts since the queue last went from idle to busy, for the start/done lines. */
    private class Batch(val started: Long) {
        val ok = java.util.concurrent.atomic.AtomicInteger()
        val failed = java.util.concurrent.atomic.AtomicInteger()
    }
    private val batch = java.util.concurrent.atomic.AtomicReference(Batch(0))

    /** Keep the process alive while downloading: foreground service when allowed, else a worker. */
    private fun startService() {
        if (!foreground.value) {
            DownloadWorker.enqueue(context)
            return
        }
        // Foreground service so a long download survives the app going to the background.
        runCatching { context.startForegroundService(Intent(context, DownloadService::class.java)) }
            .onFailure {
                AppLog.w("Downloads", "download service not started: ${it.message}", repeated = true)
                DownloadWorker.enqueue(context)
            }
    }

    private fun load(): List<Collection> = runCatching {
        if (file.exists()) json.decodeFromString(DownloadIndex.serializer(), file.readText()).collections else emptyList()
    }.getOrDefault(emptyList())

    @Synchronized
    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(DownloadIndex.serializer(), DownloadIndex(_collections.value)))
        tmp.renameTo(file)
    }
}
