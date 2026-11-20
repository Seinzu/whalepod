package dev.simplepod.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.simplepod.data.PodcastRepository
import dev.simplepod.data.backup.BackupManager
import dev.simplepod.data.backup.ImportResult
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
    private val backup: BackupManager,
) : ViewModel() {

    val podcasts: StateFlow<List<Podcast>?> = repository.observePodcasts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _add = MutableStateFlow(AddPodcastState())
    val add: StateFlow<AddPodcastState> = _add.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    /** Non-null while an export/import is running; the text describes what's happening. */
    private val _working = MutableStateFlow<String?>(null)
    val working: StateFlow<String?> = _working.asStateFlow()

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

    fun exportOpml(uri: Uri) = runWork("Exporting subscriptions\u2026") {
        val n = backup.exportOpml(uri)
        "Exported $n ${plural(n, "podcast")} to OPML"
    }

    fun exportBackup(uri: Uri) = runWork("Backing up\u2026") {
        val n = backup.exportBackup(uri)
        "Backed up $n ${plural(n, "podcast")} with listening history"
    }

    fun import(uri: Uri) = runWork("Importing\u2026") {
        when (val result = backup.import(uri)) {
            is ImportResult.Opml -> buildList {
                add("Added ${result.added} ${plural(result.added, "podcast")}")
                if (result.alreadySubscribed > 0) add("${result.alreadySubscribed} already subscribed")
                if (result.failed > 0) add("${result.failed} couldn't be fetched")
            }.joinToString(", ")
            is ImportResult.Backup -> {
                // Pull in anything published since the backup was made, without holding up the UI.
                if (result.newPodcastIds.isNotEmpty()) {
                    viewModelScope.launch { result.newPodcastIds.forEach { runCatching { repository.refresh(it) } } }
                }
                "Restored ${result.podcasts} ${plural(result.podcasts, "podcast")} and " +
                    "${result.episodesRestored} ${plural(result.episodesRestored, "episode")} of listening history"
            }
        }
    }

    private fun runWork(label: String, block: suspend () -> String) {
        if (_working.value != null) return
        viewModelScope.launch {
            _working.value = label
            _messages.value = try {
                block()
            } catch (e: Exception) {
                e.message ?: "Something went wrong"
            } finally {
                _working.value = null
            }
        }
    }

    private fun plural(n: Int, word: String) = if (n == 1) word else word + "s"

    fun messageShown() {
        _messages.value = null
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { LibraryViewModel(container().repository, container().search, container().backup) }
        }
    }
}
