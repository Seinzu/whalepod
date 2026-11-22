package dev.whalepod.ui.podcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.whalepod.data.db.Feed
import dev.whalepod.data.db.Podcast
import dev.whalepod.ui.Artwork
import dev.whalepod.ui.shortFeedLabel
import java.net.URI

/** Lists the feeds of a combined podcast, with a choice of preferred feed and a way to split one off. */
@Composable
fun FeedsSection(
    podcast: Podcast,
    feeds: List<Feed>,
    onSetPreferred: (Feed) -> Unit,
    onSplit: (Feed) -> Unit,
) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(
            "Feeds",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            "When an episode is in more than one feed, only the copy from the preferred feed is shown.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        for (feed in feeds) {
            val original = feed.url == podcast.feedUrl
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = feed.preferred, role = Role.RadioButton) { onSetPreferred(feed) }
                    .padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = feed.preferred, onClick = null, modifier = Modifier.padding(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (original) feed.title ?: podcast.title else shortFeedLabel(feed.title, podcast.title, feed.url),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(hostOf(feed.url), "original".takeIf { original }, "preferred".takeIf { feed.preferred })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!original) {
                    IconButton(onClick = { onSplit(feed) }) {
                        Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = "Split into its own podcast")
                    }
                }
            }
        }
    }
    HorizontalDivider()
}

/** Bottom sheet for adding a feed by URL, or combining another subscription into this podcast. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFeedSheet(
    state: PodcastViewModel.AddFeedState,
    otherPodcasts: List<Podcast>,
    onAddUrl: (String) -> Unit,
    onMerge: (Podcast) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().fillMaxHeight(0.9f).imePadding(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Add another feed", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Combine a second feed with this podcast — for example a private paid or ad-free " +
                            "feed. Its episodes appear here too, and it becomes the preferred feed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Feed URL") },
                        singleLine = true,
                        enabled = !state.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onAddUrl(url) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { onAddUrl(url) }, enabled = url.isNotBlank() && !state.busy) { Text("Add feed") }
                        if (state.busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            if (otherPodcasts.isNotEmpty()) {
                item {
                    Text(
                        "Or combine a podcast you already subscribe to",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
                    )
                }
                items(otherPodcasts, key = { it.id }) { other ->
                    ListItem(
                        modifier = Modifier.clickable(enabled = !state.busy) { onMerge(other) },
                        leadingContent = { Artwork(other.imageUrl, Modifier.size(40.dp), corner = 6.dp) },
                        headlineContent = { Text(other.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = hostOf(other.feedUrl)?.let { { Text(it, maxLines = 1) } },
                    )
                }
            }
        }
    }
}

@Composable
fun SplitFeedDialog(feed: Feed, podcast: Podcast, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val name = shortFeedLabel(feed.title, podcast.title, feed.url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Split off “$name”?") },
        text = {
            Text("It becomes its own podcast in your library again, taking its episodes and listening history with it.")
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Split") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun hostOf(url: String): String? = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()
