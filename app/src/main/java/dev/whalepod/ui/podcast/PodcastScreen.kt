package dev.whalepod.ui.podcast

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.whalepod.data.db.DownloadState
import dev.whalepod.data.db.Episode
import dev.whalepod.data.db.Podcast
import dev.whalepod.playback.NowPlaying
import dev.whalepod.ui.Artwork
import dev.whalepod.ui.formatDate
import dev.whalepod.ui.formatDuration
import dev.whalepod.ui.formatTime
import dev.whalepod.ui.htmlToText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastScreen(
    onBack: () -> Unit,
    vm: PodcastViewModel = viewModel(factory = PodcastViewModel.Factory),
) {
    val podcast by vm.podcast.collectAsStateWithLifecycle()
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val queued by vm.queuedIds.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmUnsubscribe by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(podcast?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Unsubscribe") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    confirmUnsubscribe = true
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val p = podcast
            if (p != null) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item(key = "header") { Header(p, onKeepChange = vm::setKeepEpisodes) }
                    val list = episodes.orEmpty()
                    if (episodes != null && list.isEmpty()) {
                        item { Text("No episodes in this feed.", Modifier.padding(24.dp)) }
                    }
                    items(list, key = { it.id }) { episode ->
                        EpisodeRow(
                            episode = episode,
                            nowPlaying = nowPlaying,
                            queued = episode.id in queued,
                            onPlay = { vm.play(episode) },
                            onToggleQueue = { vm.toggleQueued(episode) },
                            onDownload = { vm.download(episode) },
                            onDeleteDownload = { vm.deleteDownload(episode) },
                            onSetPlayed = { vm.setPlayed(episode, it) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (confirmUnsubscribe) {
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = false },
            title = { Text("Unsubscribe?") },
            text = { Text("This removes ${podcast?.title ?: "the podcast"}, its episode history and any downloaded files.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnsubscribe = false
                    vm.unsubscribe(onBack)
                }) { Text("Unsubscribe") }
            },
            dismissButton = { TextButton(onClick = { confirmUnsubscribe = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(podcast: Podcast, onKeepChange: (Boolean) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(16.dp)) {
        Row {
            Artwork(podcast.imageUrl, Modifier.size(112.dp), corner = 12.dp)
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(podcast.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                podcast.author?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val description = remember(podcast.description) { htmlToText(podcast.description) }
        if (description.isNotEmpty()) {
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp).animateContentSize().clickable { expanded = !expanded },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp).clickable { onKeepChange(!podcast.keepEpisodes) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Keep episodes after playing", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (podcast.keepEpisodes) "Downloaded files are kept" else "Files are deleted once played",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = podcast.keepEpisodes, onCheckedChange = onKeepChange)
        }
    }
    HorizontalDivider()
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    nowPlaying: NowPlaying,
    queued: Boolean,
    onPlay: () -> Unit,
    onToggleQueue: () -> Unit,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onSetPlayed: (Boolean) -> Unit,
) {
    var expanded by rememberSaveable(episode.id) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val isCurrent = nowPlaying.episodeId == episode.id
    val showPause = isCurrent && nowPlaying.isPlaying

    Column(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (episode.played) FontWeight.Normal else FontWeight.SemiBold,
                    color = if (episode.played) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    StatusIcon(episode)
                    Text(
                        subtitle(episode, isCurrent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FilledTonalIconButton(onClick = onPlay) {
                Icon(
                    if (showPause) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (showPause) "Pause" else "Play",
                )
            }
            IconButton(onClick = onToggleQueue) {
                Icon(
                    if (queued) Icons.AutoMirrored.Filled.PlaylistAddCheck else Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = if (queued) "Remove from queue" else "Add to queue",
                    tint = if (queued) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    val item = @Composable { text: String, icon: ImageVector, action: () -> Unit ->
                        DropdownMenuItem(
                            text = { Text(text) },
                            leadingIcon = { Icon(icon, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                action()
                            },
                        )
                    }
                    when (episode.downloadState) {
                        DownloadState.DOWNLOADED -> item("Delete download", Icons.Filled.Delete, onDeleteDownload)
                        DownloadState.QUEUED -> {
                            item("Download now", Icons.Filled.Download, onDownload)
                            item("Cancel download", Icons.Filled.CloudOff, onDeleteDownload)
                        }
                        DownloadState.DOWNLOADING -> item("Cancel download", Icons.Filled.CloudOff, onDeleteDownload)
                        DownloadState.NONE, DownloadState.FAILED ->
                            item(if (episode.played) "Re-download" else "Download", Icons.Filled.Download, onDownload)
                    }
                    if (episode.played) {
                        item("Mark as unplayed", Icons.Filled.RemoveDone) { onSetPlayed(false) }
                    } else {
                        item("Mark as played", Icons.Filled.CheckCircle) { onSetPlayed(true) }
                    }
                }
            }
        }

        val duration = if (isCurrent && nowPlaying.durationMs > 0) nowPlaying.durationMs else episode.durationMs
        val position = if (isCurrent) nowPlaying.positionMs else episode.positionMs
        if (!episode.played && position > 0 && duration > 0) {
            LinearProgressIndicator(
                progress = { (position.toFloat() / duration).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp),
            )
        }
        if (expanded) {
            val text = remember(episode.description) { htmlToText(episode.description) }
            if (text.isNotEmpty()) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp, end = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusIcon(episode: Episode) {
    val (icon, desc) = when {
        episode.played -> Icons.Filled.CheckCircle to "Played"
        episode.downloadState == DownloadState.DOWNLOADED -> Icons.Filled.DownloadDone to "Downloaded"
        episode.downloadState == DownloadState.QUEUED -> Icons.Filled.Schedule to "Waiting to download"
        episode.downloadState == DownloadState.DOWNLOADING -> Icons.Filled.Download to "Downloading"
        episode.downloadState == DownloadState.FAILED -> Icons.Filled.ErrorOutline to "Download failed"
        else -> return
    }
    Icon(
        icon,
        contentDescription = desc,
        modifier = Modifier.size(14.dp),
        tint = if (episode.downloadState == DownloadState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.width(4.dp))
}

private fun subtitle(episode: Episode, isCurrent: Boolean): String {
    val parts = mutableListOf<String>()
    formatDate(episode.pubDate)?.let(parts::add)
    when {
        isCurrent -> parts += "Now playing"
        !episode.played && episode.positionMs > 0 && episode.durationMs > 0 ->
            parts += "${formatTime(episode.durationMs - episode.positionMs)} left"
        !episode.played && episode.positionMs > 0 -> parts += "Resume at ${formatTime(episode.positionMs)}"
        else -> formatDuration(episode.durationMs)?.let(parts::add)
    }
    when (episode.downloadState) {
        DownloadState.DOWNLOADING -> parts += "Downloading ${episode.downloadProgress}%"
        DownloadState.QUEUED -> parts += "Queued for download"
        DownloadState.FAILED -> parts += "Download failed"
        else -> Unit
    }
    return parts.joinToString(" · ")
}
