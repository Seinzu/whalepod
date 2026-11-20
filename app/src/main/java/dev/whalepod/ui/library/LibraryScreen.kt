package dev.whalepod.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.whalepod.data.db.Podcast
import dev.whalepod.ui.Artwork
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenPodcast: (Long) -> Unit,
    vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val podcasts by vm.podcasts.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    val working by vm.working.collectAsStateWithLifecycle()
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val exportOpml = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let(vm::exportOpml) }
    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(vm::exportBackup) }
    val importFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::import) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("whalepod") },
                    actions = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Export subscriptions (OPML)") },
                                    leadingIcon = { Icon(Icons.Filled.FileUpload, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        exportOpml.launch("podcasts.opml")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Back up everything") },
                                    leadingIcon = { Icon(Icons.Filled.Backup, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        exportBackup.launch("podcast-backup-${LocalDate.now()}.json")
                                    },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Import OPML or backup") },
                                    leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        importFile.launch(arrayOf("*/*"))
                                    },
                                )
                            }
                        }
                    },
                )
                working?.let { label ->
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add podcast") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = vm::refreshAll,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val list = podcasts
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyLibrary()
                else -> PodcastGrid(list, onOpenPodcast)
            }
        }
    }

    if (showAdd) {
        AddPodcastSheet(
            vm = vm,
            subscribedUrls = podcasts.orEmpty().map { it.feedUrl }.toSet(),
            onDismiss = {
                showAdd = false
                vm.resetAddState()
            },
            onSubscribed = { id ->
                showAdd = false
                onOpenPodcast(id)
            },
        )
    }
}

@Composable
private fun EmptyLibrary() {
    // Scrollable so pull-to-refresh still works on an empty screen.
    Box(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "No podcasts yet.\nTap “Add podcast” to search for one or paste a feed URL, " +
                "or import a subscription list or backup from the ⋮ menu.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 120.dp),
        )
    }
}

@Composable
private fun PodcastGrid(podcasts: List<Podcast>, onOpen: (Long) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(podcasts, key = { it.id }) { podcast ->
            Column(Modifier.clickable { onOpen(podcast.id) }) {
                Artwork(podcast.imageUrl, Modifier.fillMaxWidth().aspectRatio(1f), corner = 12.dp)
                Text(
                    podcast.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                podcast.author?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
