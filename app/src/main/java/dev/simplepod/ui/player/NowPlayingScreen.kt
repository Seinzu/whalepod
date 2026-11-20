package dev.simplepod.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.simplepod.data.db.EpisodeWithPodcast
import dev.simplepod.playback.NowPlaying
import dev.simplepod.ui.Artwork
import dev.simplepod.ui.formatTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(onClose: () -> Unit, vm: PlayerViewModel = viewModel(factory = PlayerViewModel.Factory)) {
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Now playing") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "player") {
                if (np.episodeId == null) {
                    Text(
                        "Nothing playing",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(48.dp),
                    )
                } else {
                    PlayerControls(np, hasNext = queue.isNotEmpty(), vm)
                }
            }
            if (queue.isNotEmpty()) {
                item(key = "queue-header") {
                    Column(Modifier.fillMaxWidth()) {
                        HorizontalDivider(Modifier.padding(top = 16.dp))
                        Text(
                            "Up next",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                }
                itemsIndexed(queue, key = { _, it -> "q${it.episode.id}" }) { index, item ->
                    QueueRow(
                        item = item,
                        canMoveUp = index > 0,
                        canMoveDown = index < queue.lastIndex,
                        onPlay = { vm.playFromQueue(item.episode.id) },
                        onMove = { delta -> vm.move(item.episode.id, delta) },
                        onRemove = { vm.removeFromQueue(item.episode.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerControls(np: NowPlaying, hasNext: Boolean, vm: PlayerViewModel) {
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(np.artworkUrl, Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f), corner = 16.dp)
        Text(
            np.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            np.podcastTitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )

        val duration = np.durationMs.coerceAtLeast(0)
        val shownPosition = dragPosition?.toLong() ?: np.positionMs
        Slider(
            value = shownPosition.coerceIn(0, duration.coerceAtLeast(1)).toFloat(),
            onValueChange = { dragPosition = it },
            onValueChangeFinished = {
                dragPosition?.let { vm.seekTo(it.toLong()) }
                dragPosition = null
            },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            enabled = duration > 0,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(shownPosition), style = MaterialTheme.typography.bodySmall)
            Box(Modifier.weight(1f))
            Text(
                if (duration > 0) "-" + formatTime(duration - shownPosition) else "--:--",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = vm::seekBack, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds", Modifier.size(36.dp))
            }
            FilledIconButton(onClick = vm::togglePlayPause, modifier = Modifier.size(80.dp)) {
                when {
                    np.isBuffering && !np.isPlaying -> CircularProgressIndicator(
                        Modifier.size(32.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    else -> Icon(
                        if (np.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (np.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
            IconButton(onClick = vm::seekForward, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Forward30, contentDescription = "Forward 30 seconds", Modifier.size(36.dp))
            }
            IconButton(onClick = vm::next, enabled = hasNext, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next in queue", Modifier.size(32.dp))
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: EpisodeWithPodcast,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onPlay),
        leadingContent = { Artwork(item.podcastImageUrl, Modifier.size(48.dp), corner = 6.dp) },
        headlineContent = { Text(item.episode.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(item.podcastTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Row {
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove from queue") }
            }
        },
    )
}
