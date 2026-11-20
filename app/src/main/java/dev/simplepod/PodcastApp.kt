package dev.simplepod

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.simplepod.data.EpisodeDownloader
import dev.simplepod.data.PodcastRepository
import dev.simplepod.data.QueueRepository
import dev.simplepod.data.db.AppDatabase
import dev.simplepod.data.feed.PodcastSearch
import dev.simplepod.playback.PlayerController
import dev.simplepod.work.RefreshWorker
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-wired dependencies shared by the UI, the playback service and the workers. */
class AppContainer(context: Context) {
    val db: AppDatabase = AppDatabase.create(context)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "SimplePod/1.0 (Android)").build())
        }
        .build()

    val downloader = EpisodeDownloader(context, db)
    val repository = PodcastRepository(db, http, downloader)
    val queue = QueueRepository(db)
    val search = PodcastSearch(http)
    val player = PlayerController(context, db, queue)
}

class PodcastApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
        RefreshWorker.schedule(this)
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "downloads"
    }
}
