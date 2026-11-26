package dev.whalepod.ui.lists

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.whalepod.data.db.DownloadState
import dev.whalepod.ui.formatTime

/** Episodes that have been started but not finished, most recently played first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InProgressScreen(
    onOpenPodcast: (Long) -> Unit,
    vm: EpisodeListsViewModel = viewModel(factory = EpisodeListsViewModel.Factory),
) {
    val items by vm.inProgress.collectAsStateWithLifecycle()
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val queued by vm.queuedIds.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("In progress") }) }) { padding ->
        val list = items
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyMessage(
                "Nothing in progress.\nEpisodes you’ve started but not finished show up here.",
                Modifier.padding(padding).padding(top = 96.dp),
            )
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(list, key = { it.episode.id }) { row ->
                    val e = row.episode
                    val isCurrent = np.episodeId == e.id
                    val position = if (isCurrent) np.positionMs else e.positionMs
                    val duration = if (isCurrent && np.durationMs > 0) np.durationMs else e.durationMs
                    val detail = listOfNotNull(
                        if (duration > 0) "${formatTime(duration - position)} left" else "Resume at ${formatTime(position)}",
                        e.lastPlayedAt?.let { "played ${relativeTime(it)}" },
                        "downloaded".takeIf { e.downloadState == DownloadState.DOWNLOADED },
                    ).joinToString(" · ")
                    EpisodeListRow(
                        item = row,
                        detail = detail,
                        progress = if (duration > 0) position.toFloat() / duration else null,
                        isPlaying = isCurrent && np.isPlaying,
                        onPlay = { vm.playOrPause(e) },
                        onOpenPodcast = { onOpenPodcast(e.podcastId) },
                        actions = episodeActions(e, e.id in queued, vm),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
