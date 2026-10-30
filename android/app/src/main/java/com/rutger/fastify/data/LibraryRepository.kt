package com.rutger.fastify.data

import com.rutger.fastify.core.AlbumInfo
import com.rutger.fastify.core.FastifyCore
import com.rutger.fastify.core.TrackInfo
import com.rutger.fastify.diag.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
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
    /** Cache keys whose snapshot changed locally (playlist edits, likes): screens re-read them. */
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val changes: SharedFlow<String> = _changes

    private val _likedIds = MutableStateFlow<Set<String>?>(null)

    /** Ids in Liked Songs, from the last known liked list. */
    val likedIds: StateFlow<Set<String>?>
        get() {
            if (_likedIds.value == null) {
                cachedTracks(Collection.Liked)?.let { list -> _likedIds.value = list.mapTo(HashSet()) { it.id } }
            }
            return _likedIds
        }

    fun cachedHome(): HomeData? = store.read(HOME, HomeData.serializer())

    fun home(): Flow<Loadable<HomeData>> = swr(HOME, HomeData.serializer(), ::fetchHome)

    fun cachedNewReleases(): List<Collection>? = store.read(NEW_RELEASES, COLLECTIONS)

    fun newReleases(): Flow<Loadable<List<Collection>>> = swr(NEW_RELEASES, COLLECTIONS) {
        timed("new releases") { core().newReleases(NEW_RELEASE_DAYS.toUInt()) }.map { it.toCollection(showArtists = true) }
    }

    fun cachedTracks(c: Collection): List<Track>? = store.read(c.cacheKey, TRACKS)

    fun tracks(c: Collection): Flow<Loadable<List<Track>>> = swr(c.cacheKey, TRACKS) { fetchTracks(c) }

    fun albumHeader(albumId: String): AlbumHeader? = store.read("albumheader_$albumId", AlbumHeader.serializer())

    fun cachedArtist(id: String): ArtistPageData? = store.read("artist_$id", ArtistPageData.serializer())

    fun artist(id: String): Flow<Loadable<ArtistPageData>> = swr("artist_$id", ArtistPageData.serializer()) {
        val a = timed("artist $id") { core().artist(id) }
        ArtistPageData(
            id = a.id,
            name = a.name,
            imageUrl = a.imageUrl,
            biography = a.biography,
            topTracks = a.topTracks.map { it.toTrack() },
            albums = a.albums.map { it.toCollection() },
            singles = a.singles.map { it.toCollection() },
            compilations = a.compilations.map { it.toCollection() },
            appearsOn = a.appearsOn.map { it.toCollection(showArtists = true) },
            playcounts = a.topPlaycounts.mapValues { it.value.toLong() },
        )
    }

    /** Lyrics, cached forever per track (including "none"). */
    suspend fun lyrics(trackId: String): LyricsData? = withContext(Dispatchers.IO) {
        val key = "lyrics_$trackId"
        store.read(key, LyricsResult.serializer())?.let { return@withContext it.lyrics }
        val l = timed("lyrics $trackId") { core().lyrics(trackId) }
        val result = LyricsResult(
            l?.let { LyricsData(it.synced, it.lines.map { line -> LyricLineData(line.startMs.toLong(), line.words) }, it.provider) },
        )
        store.write(key, LyricsResult.serializer(), result)
        result.lyrics
    }

    suspend fun search(query: String): SearchResults =
        SearchResults(tracks = timed("search '$query'") { core().searchTracks(query) }.map { it.toTrack() })

    /** Playlists the user can add to: their own (owner unknown = assume editable). */
    fun editablePlaylists(username: String?): List<Collection> =
        cachedHome()?.playlists.orEmpty().filter { username == null || it.owner == null || it.owner == username }

    suspend fun addToPlaylist(playlist: Collection, track: Track) = withContext(Dispatchers.IO) {
        timed("add ${track.id} to ${playlist.id}") { core().playlistAdd(playlist.id, listOf(track.id)) }
        cachedTracks(playlist)?.let { updateSnapshot(playlist.cacheKey, it + track) }
    }

    /** Remove one row: the [occurrence]-th copy of the track in the playlist as shown. */
    suspend fun removeFromPlaylist(playlist: Collection, track: Track, occurrence: Int) = withContext(Dispatchers.IO) {
        timed("remove ${track.id} from ${playlist.id}") {
            core().playlistRemove(playlist.id, track.id, occurrence.toUInt())
        }
        cachedTracks(playlist)?.let { list ->
            var seen = -1
            val index = list.indexOfFirst { it.id == track.id && ++seen == occurrence }
            if (index >= 0) updateSnapshot(playlist.cacheKey, list.toMutableList().apply { removeAt(index) })
        }
    }

    suspend fun setLiked(track: Track, liked: Boolean) = withContext(Dispatchers.IO) {
        timed("liked=$liked ${track.id}") { core().setLiked(listOf(track.id), liked) }
        val current = cachedTracks(Collection.Liked).orEmpty().filter { it.id != track.id }
        updateSnapshot(Collection.Liked.cacheKey, if (liked) listOf(track) + current else current)
    }

    fun clear() = store.clear()

    private fun updateSnapshot(key: String, tracks: List<Track>) {
        store.write(key, TRACKS, tracks)
        if (key == Collection.Liked.cacheKey) _likedIds.value = tracks.mapTo(HashSet()) { it.id }
        _changes.tryEmit(key)
    }

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
                    it.imageUrl,
                    owner = it.owner.takeIf(String::isNotEmpty))
            },
            albums = albums.await().map { Collection(CollectionKind.ALBUM, it.id, it.name, it.artists, it.imageUrl) },
        )
    }

    private suspend fun fetchTracks(c: Collection): List<Track> = timed("tracks ${c.cacheKey}") {
        when (c.kind) {
            CollectionKind.LIKED -> core().likedTracks()
            CollectionKind.PLAYLIST -> core().playlistTracks(c.id)
            CollectionKind.ALBUM -> {
                val page = core().album(c.id)
                val a = page.album
                store.write("albumheader_${c.id}", AlbumHeader.serializer(),
                    AlbumHeader(a.id, a.artistRefs.map { ArtistRef(it.id, it.name) }, a.year, a.albumType))
                page.tracks
            }
        }
    }.map { it.toTrack() }.also { tracks ->
        if (c.kind == CollectionKind.LIKED) _likedIds.value = tracks.mapTo(HashSet()) { it.id }
    }

    private suspend fun <T> timed(what: String, block: suspend () -> T): T {
        val start = System.currentTimeMillis()
        val result = block()
        val size = (result as? kotlin.collections.Collection<*>)?.size
        AppLog.i("Library", "$what: ${size ?: "ok"} in ${System.currentTimeMillis() - start} ms")
        return result
    }

    companion object {
        private const val HOME = "home"
        private const val NEW_RELEASES = "new_releases"
        private const val NEW_RELEASE_DAYS = 60
        private val TRACKS = ListSerializer(Track.serializer())
        private val COLLECTIONS = ListSerializer(Collection.serializer())
    }
}

fun TrackInfo.toTrack() = Track(
    id, title, artists, album, imageUrl, durationMs.toLong(),
    artistRefs = artistRefs.map { ArtistRef(it.id, it.name) },
    albumId = albumId,
)

private val typeLabels = mapOf("album" to "Album", "single" to "Single", "ep" to "EP", "compilation" to "Compilation")

/** Album card: "2024 • Single", or with artists for releases by others ("Appears on", new releases). */
fun AlbumInfo.toCollection(showArtists: Boolean = false) = Collection(
    CollectionKind.ALBUM, id, name,
    listOfNotNull(
        if (showArtists) releaseDate ?: year?.toString() else year?.toString(),
        typeLabels[albumType],
        artists.takeIf { showArtists && it.isNotEmpty() },
    ).joinToString(" • "),
    imageUrl,
)
