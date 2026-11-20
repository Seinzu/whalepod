package dev.simplepod.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.simplepod.data.PodcastRepository
import dev.simplepod.data.db.Podcast
import dev.simplepod.data.feed.PodcastSearch
import dev.simplepod.data.feed.SearchResult
import dev.simplepod.ui.container
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddPodcastState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val searched: Boolean = false,
    val subscribingUrl: String? = null,
    val error: String? = null,
)

class LibraryViewModel(
    private val repository: PodcastRepository,
    private val search: PodcastSearch,
) : ViewModel() {

    val podcasts: StateFlow<List<Podcast>?> = repository.observePodcasts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _add = MutableStateFlow(AddPodcastState())
    val add: StateFlow<AddPodcastState> = _add.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(query: String) = _add.update { it.copy(query = query, error = null) }

    fun search() {
        val term = _add.value.query.trim()
        if (term.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _add.update { it.copy(searching = true, error = null) }
            runCatching { search.search(term) }
                .onSuccess { results -> _add.update { it.copy(results = results, searched = true) } }
                .onFailure { e -> _add.update { it.copy(error = "Search failed: ${e.message}") } }
            _add.update { it.copy(searching = false) }
        }
    }

    /** Subscribes; calls [onDone] with the new podcast's id on success. */
    fun subscribe(feedUrl: String, onDone: (Long) -> Unit) {
        if (feedUrl.isBlank() || _add.value.subscribingUrl != null) return
        viewModelScope.launch {
            _add.update { it.copy(subscribingUrl = feedUrl, error = null) }
            runCatching { repository.subscribe(feedUrl) }
                .onSuccess { id ->
                    _add.value = AddPodcastState()
                    onDone(id)
                }
                .onFailure { e ->
                    _add.update { it.copy(subscribingUrl = null, error = e.message ?: "Couldn't subscribe") }
                }
        }
    }

    fun resetAddState() {
        searchJob?.cancel()
        _add.value = AddPodcastState()
    }

    fun refreshAll() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            val failures = repository.refreshAll()
            _refreshing.value = false
            if (failures > 0) _messages.value = "$failures feed(s) couldn't be refreshed"
        }
    }

    fun messageShown() {
        _messages.value = null
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { LibraryViewModel(container().repository, container().search) }
        }
    }
}
