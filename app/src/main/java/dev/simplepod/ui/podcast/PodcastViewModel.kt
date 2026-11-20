package dev.simplepod.ui.podcast

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.simplepod.AppContainer
import dev.simplepod.data.db.Episode
import dev.simplepod.data.db.Podcast
import dev.simplepod.ui.container
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

    fun messageShown() {
        _message.value = null
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { PodcastViewModel(createSavedStateHandle(), container()) }
        }
    }
}
