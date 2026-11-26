package dev.whalepod.ui.lists

import android.text.format.Formatter
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.whalepod.data.db.DownloadState
import dev.whalepod.ui.formatTime

/** Downloads still in progress (or failed), then finished downloads, most recent first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onOpenPodcast: (Long) -> Unit,
    vm: EpisodeListsViewModel = viewModel(factory = EpisodeListsViewModel.Factory),
) {
    val state by vm.downloads.collectAsStateWithLifecycle()
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val queued by vm.queuedIds.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(topBar = { TopAppBar(title = { Text("Downloads") }) }) { padding ->
        val s = state
        when {
            s == null -> Unit
            s.active.isEmpty() && s.finished.isEmpty() -> EmptyMessage(
                "No downloads.\nNew episodes download automatically on Wi-Fi, " +
                    "or download one from its ⋮ menu.",
                Modifier.padding(padding).padding(top = 96.dp),
            )
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                if (s.active.isNotEmpty()) {
                    item(key = "active-header") { SectionHeader("Downloading") }
                    items(s.active, key = { it.episode.id }) { row ->
                        val e = row.episode
                        val failed = e.downloadState == DownloadState.FAILED
                        EpisodeListRow(
                            item = row,
                            detail = when (e.downloadState) {
                                DownloadState.DOWNLOADING -> "Downloading ${e.downloadProgress}%"
                                DownloadState.QUEUED -> "Waiting to download"
                                else -> "Download failed"
                            },
                            detailColor = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            progress = if (e.downloadState == DownloadState.DOWNLOADING) e.downloadProgress / 100f else null,
                            isPlaying = np.episodeId == e.id && np.isPlaying,
                            onPlay = { vm.playOrPause(e) },
                            onOpenPodcast = { onOpenPodcast(e.podcastId) },
                            actions = episodeActions(e, e.id in queued, vm),
                        )
                        HorizontalDivider()
                    }
                }
                if (s.finished.isNotEmpty()) {
                    item(key = "finished-header") {
                        val count = s.finished.size
                        SectionHeader(
                            "Downloaded · $count ${if (count == 1) "episode" else "episodes"} · " +
                                Formatter.formatShortFileSize(context, s.totalBytes),
                        )
                    }
                    items(s.finished, key = { it.episode.id }) { row ->
                        val e = row.episode
                        val isCurrent = np.episodeId == e.id
                        val position = if (isCurrent) np.positionMs else e.positionMs
                        val duration = if (isCurrent && np.durationMs > 0) np.durationMs else e.durationMs
                        val started = !e.played && position > 0
                        val detail = listOfNotNull(
                            e.downloadedAt?.let { "Downloaded ${relativeTime(it)}" } ?: "Downloaded",
                            s.sizes[e.id]?.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
                            when {
                                e.played -> "played"
                                started && duration > 0 -> "${formatTime(duration - position)} left"
                                else -> null
                            },
                        ).joinToString(" · ")
                        EpisodeListRow(
                            item = row,
                            detail = detail,
                            progress = if (started && duration > 0) position.toFloat() / duration else null,
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
}
