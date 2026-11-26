package dev.whalepod.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Checks what the In progress and Downloads views include, and their order. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EpisodeListQueriesTest {
    private lateinit var db: AppDatabase
    private var podcastId = 0L
    private var feedId = 0L

    @Before
    fun setUp() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        podcastId = db.podcastDao().insert(Podcast(feedUrl = "https://e/feed", title = "Show"))
        feedId = db.feedDao().insert(Feed(podcastId = podcastId, url = "https://e/feed", preferred = true))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun episode(guid: String, configure: Episode.() -> Episode = { this }): Long =
        db.episodeDao().insertAll(
            listOf(
                Episode(podcastId = podcastId, feedId = feedId, guid = guid, title = guid, audioUrl = "https://e/$guid.mp3")
                    .configure(),
            ),
        ).single()

    @Test
    fun inProgressListsStartedUnfinishedEpisodesMostRecentFirst() = runBlocking {
        episode("older") { copy(positionMs = 1000, lastPlayedAt = 100) }
        episode("newer") { copy(positionMs = 2000, lastPlayedAt = 200) }
        episode("noTimestamp") { copy(positionMs = 3000) }
        episode("notStarted")
        episode("finished") { copy(positionMs = 5000, played = true, lastPlayedAt = 300) }
        val kept = episode("kept") { copy(positionMs = 1, lastPlayedAt = 1) }
        episode("hiddenDuplicate") { copy(positionMs = 9000, lastPlayedAt = 999, duplicateOfId = kept) }

        val titles = db.episodeDao().observeInProgress().first().map { it.episode.title }
        assertEquals(listOf("newer", "older", "kept", "noTimestamp"), titles)
        assertEquals("Show", db.episodeDao().observeInProgress().first().first().podcastTitle)
    }

    @Test
    fun downloadsListActiveFirstThenMostRecentlyDownloaded() = runBlocking {
        episode("notDownloaded")
        episode("oldDownload") { copy(downloadState = DownloadState.DOWNLOADED, downloadedAt = 100, pubDate = 9) }
        episode("newDownload") { copy(downloadState = DownloadState.DOWNLOADED, downloadedAt = 200, pubDate = 1) }
        episode("preUpgradeDownload") { copy(downloadState = DownloadState.DOWNLOADED, pubDate = 50) }
        episode("waiting") { copy(downloadState = DownloadState.QUEUED) }
        episode("failed") { copy(downloadState = DownloadState.FAILED) }
        episode("downloading") { copy(downloadState = DownloadState.DOWNLOADING, downloadProgress = 40) }
        val kept = episode("kept") { copy(downloadState = DownloadState.DOWNLOADED, downloadedAt = 50) }
        episode("hiddenDuplicate") { copy(downloadState = DownloadState.DOWNLOADED, downloadedAt = 999, duplicateOfId = kept) }

        val titles = db.episodeDao().observeDownloads().first().map { it.episode.title }
        assertEquals(
            listOf("downloading", "waiting", "failed", "newDownload", "oldDownload", "kept", "preUpgradeDownload"),
            titles,
        )
    }
}
