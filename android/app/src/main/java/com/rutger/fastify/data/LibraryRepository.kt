package com.rutger.fastify.data

import com.rutger.fastify.core.FastifyCore
import com.rutger.fastify.core.TrackInfo
import com.rutger.fastify.diag.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/**
 * Stale-while-revalidate access to the library. Everything comes from Spotify's internal APIs
 * through the librespot session (the public Web API 429s for our client id).
 * "Recently played" is Fastify's own play history.
 */
class LibraryRepository(
    private val core: () -> FastifyCore,
    private val store: SnapshotStore,
    private val history: PlayHistory,
) {
    fun cachedHome(): HomeData? = store.read(HOME, HomeData.serializer())

    fun home(): Flow<Loadable<HomeData>> = swr(HOME, HomeData.serializer(), ::fetchHome)

    fun cachedTracks(c: Collection): List<Track>? = store.read(c.cacheKey, TRACKS)

    fun tracks(c: Collection): Flow<Loadable<List<Track>>> = swr(c.cacheKey, TRACKS) { fetchTracks(c) }

    suspend fun search(query: String): SearchResults =
        SearchResults(tracks = timed("search '$query'") { core().searchTracks(query) }.map { it.toTrack() })

    fun clear() = store.clear()

    private fun <T : Any> swr(key: String, serializer: KSerializer<T>, fetch: suspend () -> T) = flow {
        val cached = store.read(key, serializer)
        emit(Loadable(cached, refreshing = true))
        try {
            val fresh = fetch()
            store.write(key, serializer, fresh)
            emit(Loadable(fresh, refreshing = false))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLog.w("Library", "refresh $key failed", e)
            emit(Loadable(cached, refreshing = false, error = "${e.javaClass.simpleName}: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun fetchHome(): HomeData = coroutineScope {
        // Playlists are the core of the screen; albums/history failing shouldn't blank it.
        val playlists = async { timed("playlists") { core().playlists() } }
        val albums = async {
            runCatching { timed("albums") { core().savedAlbums() } }
                .onFailure { AppLog.w("Library", "saved albums failed: ${it.message}") }
                .getOrDefault(emptyList())
        }
        HomeData(
            recent = history.recent(),
            playlists = playlists.await().map {
                Collection(CollectionKind.PLAYLIST, it.id, it.name.ifEmpty { "Untitled" },
                    listOfNotNull(it.owner.takeIf(String::isNotEmpty)?.let { o -> "By $o" },
                        "${it.length} songs").joinToString(" • "),
                    it.imageUrl)
            },
            albums = albums.await().map { Collection(CollectionKind.ALBUM, it.id, it.name, it.artists, it.imageUrl) },
        )
    }

    private suspend fun fetchTracks(c: Collection): List<Track> = timed("tracks ${c.cacheKey}") {
        when (c.kind) {
            CollectionKind.LIKED -> core().likedTracks()
            CollectionKind.PLAYLIST -> core().playlistTracks(c.id)
            CollectionKind.ALBUM -> core().albumTracks(c.id)
        }
    }.map { it.toTrack() }

    private suspend fun <T> timed(what: String, block: suspend () -> T): T {
        val start = System.currentTimeMillis()
        val result = block()
        val size = (result as? kotlin.collections.Collection<*>)?.size
        AppLog.i("Library", "$what: ${size ?: "ok"} in ${System.currentTimeMillis() - start} ms")
        return result
    }

    companion object {
        private const val HOME = "home"
        private val TRACKS = ListSerializer(Track.serializer())
    }
}

fun TrackInfo.toTrack() = Track(id, title, artists, album, imageUrl, durationMs.toLong())
