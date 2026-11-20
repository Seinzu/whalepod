package dev.simplepod

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
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
import dev.simplepod.ui.library.LibraryScreen
import dev.simplepod.ui.player.MiniPlayer
import dev.simplepod.ui.player.NowPlayingScreen
import dev.simplepod.ui.podcast.PodcastScreen
import dev.simplepod.ui.theme.SimplePodTheme

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
            SimplePodTheme {
                val nav = rememberNavController()
                val backStack by nav.currentBackStackEntryAsState()
                val onPlayerScreen = backStack?.destination?.route == ROUTE_PLAYER

                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        if (!onPlayerScreen) {
                            MiniPlayer(onOpen = { nav.navigate(ROUTE_PLAYER) { launchSingleTop = true } })
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                        NavHost(nav, startDestination = ROUTE_LIBRARY) {
                            composable(ROUTE_LIBRARY) {
                                LibraryScreen(onOpenPodcast = { id -> nav.navigate("podcast/$id") })
                            }
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
    }
}
