package dev.whalepod

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.whalepod.ui.library.LibraryScreen
import dev.whalepod.ui.lists.DownloadsScreen
import dev.whalepod.ui.lists.InProgressScreen
import dev.whalepod.ui.player.MiniPlayer
import dev.whalepod.ui.player.NowPlayingScreen
import dev.whalepod.ui.podcast.PodcastScreen
import dev.whalepod.ui.theme.WhalepodTheme

class MainActivity : ComponentActivity() {

    private val container get() = (application as PodcastApp).container

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            WhalepodTheme {
                val nav = rememberNavController()
                val backStack by nav.currentBackStackEntryAsState()
                val route = backStack?.destination?.route
                val onPlayerScreen = route == ROUTE_PLAYER
                val onTopLevel = TOP_LEVEL.any { it.route == route }
                val openPodcast = { id: Long -> nav.navigate("podcast/$id") }

                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        if (!onPlayerScreen) {
                            Column {
                                MiniPlayer(
                                    onOpen = { nav.navigate(ROUTE_PLAYER) { launchSingleTop = true } },
                                    padForSystemBar = !onTopLevel,
                                )
                                if (onTopLevel) {
                                    NavigationBar {
                                        for (dest in TOP_LEVEL) {
                                            NavigationBarItem(
                                                selected = route == dest.route,
                                                onClick = {
                                                    nav.navigate(dest.route) {
                                                        // Standard bottom-navigation behaviour: one copy of each
                                                        // tab, each remembering its scroll position.
                                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                },
                                                icon = { Icon(dest.icon, contentDescription = null) },
                                                label = { Text(dest.label) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                        NavHost(nav, startDestination = ROUTE_LIBRARY) {
                            composable(ROUTE_LIBRARY) {
                                LibraryScreen(onOpenPodcast = openPodcast)
                            }
                            composable(ROUTE_IN_PROGRESS) { InProgressScreen(onOpenPodcast = openPodcast) }
                            composable(ROUTE_DOWNLOADS) { DownloadsScreen(onOpenPodcast = openPodcast) }
                            composable(
                                ROUTE_PODCAST,
                                arguments = listOf(navArgument("podcastId") { type = NavType.LongType }),
                            ) {
                                PodcastScreen(onBack = { nav.popBackStack() })
                            }
                            composable(ROUTE_PLAYER) {
                                NowPlayingScreen(onClose = { nav.popBackStack() })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.player.connect()
    }

    override fun onStop() {
        container.player.disconnect()
        super.onStop()
    }

    private companion object {
        const val ROUTE_LIBRARY = "library"
        const val ROUTE_PODCAST = "podcast/{podcastId}"
        const val ROUTE_PLAYER = "player"
        const val ROUTE_IN_PROGRESS = "in_progress"
        const val ROUTE_DOWNLOADS = "downloads"

        class TopLevel(val route: String, val label: String, val icon: ImageVector)

        val TOP_LEVEL = listOf(
            TopLevel(ROUTE_LIBRARY, "Library", Icons.Filled.Podcasts),
            TopLevel(ROUTE_IN_PROGRESS, "In progress", Icons.Filled.Timelapse),
            TopLevel(ROUTE_DOWNLOADS, "Downloads", Icons.Filled.DownloadForOffline),
        )
    }
}
