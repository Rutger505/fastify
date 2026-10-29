package com.rutger.fastify.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable

/** Stale-while-revalidate access to the user's library. */
class LibraryRepository(private val api: WebApi, private val store: SnapshotStore) {

    fun cachedHome(): HomeData? = store.read(HOME, HomeData.serializer())

    fun home(): Flow<Loadable<HomeData>> = swr(HOME, HomeData.serializer(), ::fetchHome)

    fun cachedTracks(c: Collection): List<Track>? = store.read(c.cacheKey, TRACKS)

    fun tracks(c: Collection): Flow<Loadable<List<Track>>> = swr(c.cacheKey, TRACKS) { fetchTracks(c) }

    suspend fun search(query: String): SearchResults {
        val q = Uri.encode(query)
        val dto = api.get("/search?q=$q&type=track,album,playlist&limit=20", SearchDto.serializer())
        return SearchResults(
            tracks = dto.tracks?.items.orEmpty().mapNotNull { it?.toTrack() },
            albums = dto.albums?.items.orEmpty().mapNotNull { it?.toCollection() },
            playlists = dto.playlists?.items.orEmpty().mapNotNull { it?.toCollection() },
        )
    }

    fun clear() = store.clear()

    private fun <T : Any> swr(key: String, serializer: KSerializer<T>, fetch: suspend () -> T) = flow {
        val cached = store.read(key, serializer)
        emit(Loadable(cached, refreshing = true))
        try {
            val fresh = fetch()
            store.write(key, serializer, fresh)
            emit(Loadable(fresh, refreshing = false))
        } catch (e: Exception) {
            emit(Loadable(cached, refreshing = false, error = e.message ?: e.javaClass.simpleName))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun fetchHome(): HomeData = coroutineScope {
        val recent = async {
            runCatching {
                api.get("/me/player/recently-played?limit=30", Paging.serializer(PlayHistoryDto.serializer()))
                    .items.mapNotNull { it.track?.toTrack() }.distinctBy { it.id }
            }.getOrDefault(emptyList())
        }
        val playlists = async {
            pages("/me/playlists?limit=50", 50, PlaylistDto.serializer().nullable).mapNotNull { it?.toCollection() }
        }
        val albums = async {
            pages("/me/albums?limit=50", 50, SavedAlbumDto.serializer()).mapNotNull { it.album?.toCollection() }
        }
        HomeData(recent.await(), playlists.await(), albums.await())
    }

    private suspend fun fetchTracks(c: Collection): List<Track> = when (c.kind) {
        CollectionKind.LIKED ->
            pages("/me/tracks?limit=50", 50, SavedTrackDto.serializer()).mapNotNull { it.track?.toTrack() }

        CollectionKind.ALBUM -> {
            val album = AlbumDto(id = c.id, name = c.title, images = c.imageUrl?.let { listOf(ImageDto(it)) })
            pages("/albums/${c.id}/tracks?limit=50", 50, TrackDto.serializer()).mapNotNull { it.toTrack(album) }
        }

        CollectionKind.PLAYLIST -> {
            val fields = "items(%s(id,name,type,is_local,duration_ms,artists(name),album(name,images))),next,total"
            try {
                pages("/playlists/${c.id}/tracks?limit=100&fields=" + Uri.encode(fields.format("track")), 100, PlaylistItemDto.serializer())
            } catch (e: ApiException) {
                if (e.code != 403 && e.code != 404) throw e
                pages("/playlists/${c.id}/items?limit=100&fields=" + Uri.encode(fields.format("item")), 100, PlaylistItemDto.serializer())
            }.mapNotNull { (it.track ?: it.item)?.toTrack() }
        }
    }

    /** First page tells the total; the rest is fetched concurrently (4 at a time). */
    private suspend fun <T> pages(path: String, pageSize: Int, item: KSerializer<T>): List<T> = coroutineScope {
        val serializer = Paging.serializer(item)
        val first = api.get(path, serializer)
        if (first.next == null) return@coroutineScope first.items
        val offsets = (pageSize until minOf(first.total, MAX_ITEMS) step pageSize).toList()
        val gate = Semaphore(4)
        val rest = offsets.map { offset ->
            async { gate.withPermit { api.get("$path&offset=$offset", serializer).items } }
        }.awaitAll()
        first.items + rest.flatten()
    }

    companion object {
        private const val HOME = "home"
        private const val MAX_ITEMS = 10_000
        private val TRACKS = ListSerializer(Track.serializer())
    }
}
