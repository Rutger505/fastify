package com.rutger.fastify.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rutger.fastify.data.Collection
import com.rutger.fastify.data.HomeData
import com.rutger.fastify.data.LibraryRepository
import com.rutger.fastify.data.Loadable
import com.rutger.fastify.data.SearchResults
import com.rutger.fastify.data.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val repo: LibraryRepository) : ViewModel() {
    private val refresh = MutableStateFlow(0)

    // Initial value is read synchronously from the snapshot so the first frame already has content.
    val state: StateFlow<Loadable<HomeData>> = refresh
        .flatMapLatest { repo.home() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Loadable(repo.cachedHome(), refreshing = true))

    fun refresh() {
        refresh.value++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionViewModel(private val repo: LibraryRepository, private val collection: Collection) : ViewModel() {
    private val refresh = MutableStateFlow(0)

    val state: StateFlow<Loadable<List<Track>>> = refresh
        .flatMapLatest { repo.tracks(collection) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Loadable(repo.cachedTracks(collection), refreshing = true))

    fun refresh() {
        refresh.value++
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
                        SearchState(q, error = e.message)
                    },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchState())
}
