package dev.whalepod.ui.podcast

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.whalepod.AppContainer
import dev.whalepod.data.db.Episode
import dev.whalepod.data.db.Feed
import dev.whalepod.data.db.Podcast
import dev.whalepod.ui.container
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PodcastViewModel(savedState: SavedStateHandle, private val app: AppContainer) : ViewModel() {
    val podcastId: Long = checkNotNull(savedState.get<Long>("podcastId"))
    private val repository = app.repository

    val podcast: StateFlow<Podcast?> = repository.observePodcast(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val episodes: StateFlow<List<Episode>?> = repository.observeEpisodes(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val feeds: StateFlow<List<Feed>> = repository.observeFeeds(podcastId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Other subscriptions that could be merged into this podcast. */
    val otherPodcasts: StateFlow<List<Podcast>> = repository.observePodcasts()
        .map { list -> list.filter { it.id != podcastId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Progress and errors for the "Add another feed" sheet. */
    data class AddFeedState(val busy: Boolean = false, val error: String? = null)

    private val _addFeed = MutableStateFlow(AddFeedState())
    val addFeed: StateFlow<AddFeedState> = _addFeed.asStateFlow()

    val queuedIds: StateFlow<Set<Long>> = app.queue.observe()
        .map { list -> list.map { it.episode.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val nowPlaying = app.player.state

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            runCatching { repository.refresh(podcastId) }
                .onFailure { _message.value = "Refresh failed: ${it.message}" }
            _refreshing.value = false
        }
    }

    fun play(episode: Episode) {
        val np = nowPlaying.value
        if (np.episodeId == episode.id) app.player.togglePlayPause() else app.player.play(episode.id)
    }

    fun toggleQueued(episode: Episode) = viewModelScope.launch {
        if (episode.id in queuedIds.value) {
            app.queue.remove(episode.id)
        } else {
            app.queue.add(episode.id)
            _message.value = "Added to queue"
        }
    }

    fun download(episode: Episode) = viewModelScope.launch { repository.download(episode.id) }
    fun deleteDownload(episode: Episode) = viewModelScope.launch { repository.deleteDownload(episode.id) }
    fun setPlayed(episode: Episode, played: Boolean) = viewModelScope.launch {
        if (played && nowPlaying.value.episodeId == episode.id) app.player.stop()
        repository.setPlayed(episode.id, played)
    }

    fun setKeepEpisodes(keep: Boolean) = viewModelScope.launch { repository.setKeepEpisodes(podcastId, keep) }

    fun unsubscribe(onDone: () -> Unit) = viewModelScope.launch {
        if (nowPlaying.value.podcastId == podcastId) app.player.stop()
        repository.unsubscribe(podcastId)
        onDone()
    }

    fun resetAddFeed() {
        _addFeed.value = AddFeedState()
    }

    /** Adds a feed by URL (merging in another subscription if it already has that feed). */
    fun addFeed(url: String, onDone: () -> Unit) {
        if (url.isBlank() || _addFeed.value.busy) return
        viewModelScope.launch {
            _addFeed.value = AddFeedState(busy = true)
            try {
                repository.addFeed(podcastId, url)
                _addFeed.value = AddFeedState()
                _message.value = "Feed added"
                onDone()
            } catch (e: Exception) {
                _addFeed.value = AddFeedState(error = e.message ?: "Couldn't add that feed")
            }
        }
    }

    fun mergeIn(other: Podcast, onDone: () -> Unit) {
        if (_addFeed.value.busy) return
        viewModelScope.launch {
            _addFeed.value = AddFeedState(busy = true)
            try {
                repository.mergePodcasts(podcastId, other.id)
                _addFeed.value = AddFeedState()
                _message.value = "Combined \u201c${other.title}\u201d into this podcast"
                onDone()
            } catch (e: Exception) {
                _addFeed.value = AddFeedState(error = e.message ?: "Couldn't combine those podcasts")
            }
        }
    }

    fun setPreferredFeed(feed: Feed) = viewModelScope.launch { repository.setPreferredFeed(podcastId, feed.id) }

    fun splitFeed(feed: Feed) = viewModelScope.launch {
        runCatching { repository.splitFeed(podcastId, feed.id) }
            .onSuccess { _message.value = "Split into its own podcast" }
            .onFailure { _message.value = it.message ?: "Couldn't split that feed" }
    }

    fun messageShown() {
        _message.value = null
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { PodcastViewModel(createSavedStateHandle(), container()) }
        }
    }
}
