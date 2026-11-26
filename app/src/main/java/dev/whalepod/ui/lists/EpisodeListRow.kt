package dev.whalepod.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.whalepod.data.db.EpisodeWithPodcast
import dev.whalepod.ui.Artwork

data class RowAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * An episode in a cross-podcast list: artwork, title, podcast name, a detail line, an optional progress
 * bar, a play/pause button and a menu of [actions]. Tapping the row opens the podcast.
 */
@Composable
fun EpisodeListRow(
    item: EpisodeWithPodcast,
    detail: String,
    progress: Float?,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onOpenPodcast: () -> Unit,
    actions: List<RowAction>,
    detailColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenPodcast)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(item.podcastImageUrl, Modifier.size(52.dp), corner = 8.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                item.episode.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.podcastTitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(detail, style = MaterialTheme.typography.bodySmall, color = detailColor, maxLines = 1)
            progress?.let {
                LinearProgressIndicator(
                    progress = { it.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    drawStopIndicator = {},
                )
            }
        }
        FilledTonalIconButton(onClick = onPlay) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                for (action in actions) {
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        leadingIcon = { Icon(action.icon, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            action.onClick()
                        },
                    )
                }
            }
        }
    }
}

/** A small heading above a group of rows. */
@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** Centred message for an empty list. */
@Composable
fun EmptyMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
