package dev.whalepod.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whalepod.data.PodcastRepository
import dev.whalepod.data.feed.SearchResult
import dev.whalepod.ui.Artwork

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPodcastSheet(
    vm: LibraryViewModel,
    subscribedUrls: Set<String>,
    onDismiss: () -> Unit,
    onSubscribed: (Long) -> Unit,
) {
    val state by vm.add.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).imePadding()) {
            Text(
                "Add a podcast",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Search") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("RSS URL") })
            }
            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            when (tab) {
                0 -> SearchTab(state, subscribedUrls, vm, onSubscribed)
                else -> UrlTab(state, vm, onSubscribed)
            }
        }
    }
}

@Composable
private fun SearchTab(
    state: AddPodcastState,
    subscribedUrls: Set<String>,
    vm: LibraryViewModel,
    onSubscribed: (Long) -> Unit,
) {
    OutlinedTextField(
        value = state.query,
        onValueChange = vm::onQueryChange,
        label = { Text("Podcast name") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { vm.search() }),
        trailingIcon = {
            IconButton(onClick = vm::search) { Icon(Icons.Filled.Search, contentDescription = "Search") }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
    when {
        state.searching -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
            CircularProgressIndicator()
        }
        state.searched && state.results.isEmpty() -> Text(
            "No podcasts found.",
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(state.results, key = { it.collectionId ?: it.feedUrl.hashCode().toLong() }) { result ->
                SearchResultRow(
                    result = result,
                    subscribed = PodcastRepository.normaliseFeedUrl(result.feedUrl.orEmpty()) in subscribedUrls,
                    subscribing = state.subscribingUrl == result.feedUrl,
                    busy = state.subscribingUrl != null,
                    onSubscribe = { vm.subscribe(result.feedUrl.orEmpty(), onSubscribed) },
                )
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    result: SearchResult,
    subscribed: Boolean,
    subscribing: Boolean,
    busy: Boolean,
    onSubscribe: () -> Unit,
) {
    ListItem(
        leadingContent = { Artwork(result.artworkUrl, Modifier.size(56.dp)) },
        headlineContent = {
            Text(result.collectionName ?: "Untitled", maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = result.artistName?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            when {
                subscribed -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Subscribed",
                    tint = MaterialTheme.colorScheme.primary,
                )
                subscribing -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else -> FilledTonalButton(onClick = onSubscribe, enabled = !busy) { Text("Add") }
            }
        },
    )
}

@Composable
private fun UrlTab(state: AddPodcastState, vm: LibraryViewModel, onSubscribed: (Long) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    val subscribe = { vm.subscribe(url, onSubscribed) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Feed URL") },
            placeholder = { Text("https://example.com/feed.xml") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { subscribe() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = subscribe, enabled = url.isNotBlank() && state.subscribingUrl == null) {
                Text("Subscribe")
            }
            if (state.subscribingUrl != null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}
