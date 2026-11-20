package dev.simplepod.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.simplepod.AppContainer
import dev.simplepod.data.db.EpisodeWithPodcast
import dev.simplepod.playback.NowPlaying
import dev.simplepod.ui.container
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlayerViewModel(private val app: AppContainer) : ViewModel() {
    val nowPlaying: StateFlow<NowPlaying> = app.player.state

    val queue: StateFlow<List<EpisodeWithPodcast>> = app.queue.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun togglePlayPause() = app.player.togglePlayPause()
    fun seekBack() = app.player.seekBack()
    fun seekForward() = app.player.seekForward()
    fun seekTo(ms: Long) = app.player.seekTo(ms)
    fun next() = app.player.next()
    fun playFromQueue(episodeId: Long) = app.player.play(episodeId)
    fun removeFromQueue(episodeId: Long) = viewModelScope.launch { app.queue.remove(episodeId) }
    fun move(episodeId: Long, delta: Int) = viewModelScope.launch { app.queue.move(episodeId, delta) }

    companion object {
        val Factory = viewModelFactory { initializer { PlayerViewModel(container()) } }
    }
}
