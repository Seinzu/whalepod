package dev.whalepod.ui.lists

import android.text.format.DateUtils
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RemoveDone
import dev.whalepod.data.db.DownloadState
import dev.whalepod.data.db.Episode

/** The menu actions for an episode in the In progress and Downloads views. */
fun episodeActions(episode: Episode, queued: Boolean, vm: EpisodeListsViewModel): List<RowAction> = buildList {
    add(
        if (queued) {
            RowAction("Remove from queue", Icons.AutoMirrored.Filled.PlaylistAddCheck) { vm.toggleQueued(episode) }
        } else {
            RowAction("Add to queue", Icons.AutoMirrored.Filled.PlaylistAdd) { vm.toggleQueued(episode) }
        },
    )
    when (episode.downloadState) {
        DownloadState.DOWNLOADED -> add(RowAction("Delete download", Icons.Filled.Delete) { vm.deleteDownload(episode) })
        DownloadState.QUEUED -> {
            add(RowAction("Download now", Icons.Filled.Download) { vm.download(episode) })
            add(RowAction("Cancel download", Icons.Filled.CloudOff) { vm.deleteDownload(episode) })
        }
        DownloadState.DOWNLOADING -> add(RowAction("Cancel download", Icons.Filled.CloudOff) { vm.deleteDownload(episode) })
        DownloadState.FAILED -> {
            add(RowAction("Retry download", Icons.Filled.Download) { vm.download(episode) })
            add(RowAction("Dismiss", Icons.Filled.CloudOff) { vm.deleteDownload(episode) })
        }
        DownloadState.NONE -> add(RowAction("Download", Icons.Filled.Download) { vm.download(episode) })
    }
    add(
        if (episode.played) {
            RowAction("Mark as unplayed", Icons.Filled.RemoveDone) { vm.setPlayed(episode, false) }
        } else {
            RowAction("Mark as played", Icons.Filled.CheckCircle) { vm.setPlayed(episode, true) }
        },
    )
}

/** "2 hours ago", "yesterday", … */
fun relativeTime(epochMs: Long): String =
    DateUtils.getRelativeTimeSpanString(epochMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        .toString()
        .replaceFirstChar { it.lowercase() }
