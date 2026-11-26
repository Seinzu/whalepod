package dev.whalepod.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.whalepod.AppContainer
import dev.whalepod.data.db.DownloadState
import dev.whalepod.data.db.Episode
import dev.whalepod.data.db.EpisodeWithPodcast
import dev.whalepod.playback.NowPlaying
import dev.whalepod.ui.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** The Downloads view's data: downloads still to finish, finished ones, and the space they take up. */
data class DownloadsState(
    val active: List<EpisodeWithPodcast> = emptyList(),
    val finished: List<EpisodeWithPodcast> = emptyList(),
    /** Size on disk of each finished download, by episode id. */
    val sizes: Map<Long, Long> = emptyMap(),
) {
    val totalBytes: Long get() = sizes.values.sum()
}

/** Shared by the In progress and Downloads views. */
class EpisodeListsViewModel(private val app: AppContainer) : ViewModel() {
    private val episodeDao = app.db.episodeDao()

    val nowPlaying: StateFlow<NowPlaying> = app.player.state

    /** null until the first load, so the screen can tell "loading" from "empty". */
    val inProgress: StateFlow<List<EpisodeWithPodcast>?> = episodeDao.observeInProgress()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val downloads: StateFlow<DownloadsState?> = episodeDao.observeDownloads()
        .map { rows ->
            val (finished, active) = rows.partition { it.episode.downloadState == DownloadState.DOWNLOADED }
            DownloadsState(
                active = active,
                finished = finished,
                sizes = finished.associate { row ->
                    row.episode.id to (row.episode.localFilePath?.let { File(it).length() } ?: 0L)
                },
            )
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val queuedIds: StateFlow<Set<Long>> = app.queue.observe()
        .map { list -> list.map { it.episode.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun playOrPause(episode: Episode) {
        if (nowPlaying.value.episodeId == episode.id) app.player.togglePlayPause() else app.player.play(episode.id)
    }

    fun toggleQueued(episode: Episode) = viewModelScope.launch {
        if (episode.id in queuedIds.value) app.queue.remove(episode.id) else app.queue.add(episode.id)
    }

    fun download(episode: Episode) = viewModelScope.launch { app.repository.download(episode.id) }

    fun deleteDownload(episode: Episode) = viewModelScope.launch { app.repository.deleteDownload(episode.id) }

    fun setPlayed(episode: Episode, played: Boolean) = viewModelScope.launch {
        if (played && nowPlaying.value.episodeId == episode.id) app.player.stop()
        app.repository.setPlayed(episode.id, played)
    }

    companion object {
        val Factory = viewModelFactory { initializer { EpisodeListsViewModel(container()) } }
    }
}
