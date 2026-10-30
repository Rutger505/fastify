package com.rutger.fastify.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rutger.fastify.data.ArtistPageData
import com.rutger.fastify.data.Collection
import com.rutger.fastify.data.Downloads
import com.rutger.fastify.data.HomeData
import com.rutger.fastify.data.LibraryRepository
import com.rutger.fastify.data.Loadable
import com.rutger.fastify.data.SearchResults
import com.rutger.fastify.data.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Cache-first screen state: the snapshot for the first frame, then network refreshes.
 * Subclasses call [refresh] at the end of their init (after their own properties exist).
 */
open class SwrViewModel<T>(initial: T?, private val load: () -> Flow<Loadable<T>>) : ViewModel() {
    protected val _state = MutableStateFlow(Loadable(initial, refreshing = true))
    val state: StateFlow<Loadable<T>> = _state
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch { load().collect { onLoaded(it) } }
    }

    protected open fun onLoaded(value: Loadable<T>) {
        _state.value = value
    }
}

class HomeViewModel(repo: LibraryRepository) : SwrViewModel<HomeData>(repo.cachedHome(), repo::home) {
    init {
        refresh()
    }
}

class NewReleasesViewModel(repo: LibraryRepository) :
    SwrViewModel<List<Collection>>(repo.cachedNewReleases(), repo::newReleases) {
    init {
        refresh()
    }
}

class ArtistViewModel(repo: LibraryRepository, id: String) :
    SwrViewModel<ArtistPageData>(repo.cachedArtist(id), { repo.artist(id) }) {
    init {
        refresh()
    }
}

class CollectionViewModel(
    private val repo: LibraryRepository,
    private val downloads: Downloads,
    private val collection: Collection,
) : SwrViewModel<List<Track>>(repo.cachedTracks(collection), { repo.tracks(collection) }) {
    init {
        // Local edits (remove from playlist, like) rewrite the snapshot: show them right away.
        viewModelScope.launch {
            repo.changes.filter { it == collection.cacheKey }.collect {
                val tracks = repo.cachedTracks(collection)
                _state.update { it.copy(data = tracks) }
                if (tracks != null) downloads.sync(collection, tracks)
            }
        }
        refresh()
    }

    override fun onLoaded(value: Loadable<List<Track>>) {
        super.onLoaded(value)
        // Fresh list from the network: keep a downloaded collection in sync.
        if (!value.refreshing && value.error == null) value.data?.let { downloads.sync(collection, it) }
    }
}

data class SearchState(val query: String = "", val loading: Boolean = false, val results: SearchResults? = null, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(private val repo: LibraryRepository) : ViewModel() {
    val query = MutableStateFlow("")

    val state: StateFlow<SearchState> = query
        .drop(1)
        .debounce(250)
        .distinctUntilChanged()
        .flatMapLatest { q ->
            flow {
                if (q.isBlank()) {
                    emit(SearchState(q))
                    return@flow
                }
                emit(SearchState(q, loading = true))
                emit(
                    try {
                        SearchState(q, results = repo.search(q))
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        com.rutger.fastify.diag.AppLog.w("Search", "search '$q' failed", e)
                        SearchState(q, error = "${e.javaClass.simpleName}: ${e.message}")
                    },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchState())
}
