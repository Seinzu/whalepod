package dev.whalepod.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Builds a database exactly as version 1 of the app created it, then opens it with the current schema. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    // Copied from the Room-generated createAllTables() of database version 1.
    private val v1Schema = listOf(
        "CREATE TABLE IF NOT EXISTS `podcasts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `feedUrl` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT, `description` TEXT, `imageUrl` TEXT, `keepEpisodes` INTEGER NOT NULL, `lastRefreshedAt` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_podcasts_feedUrl` ON `podcasts` (`feedUrl`)",
        "CREATE TABLE IF NOT EXISTS `episodes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `podcastId` INTEGER NOT NULL, `guid` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT, `pubDate` INTEGER NOT NULL, `audioUrl` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `localFilePath` TEXT, `downloadState` TEXT NOT NULL, `downloadProgress` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, `played` INTEGER NOT NULL, `lastPlayedAt` INTEGER, FOREIGN KEY(`podcastId`) REFERENCES `podcasts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_episodes_podcastId_guid` ON `episodes` (`podcastId`, `guid`)",
        "CREATE TABLE IF NOT EXISTS `queue_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `episodeId` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, FOREIGN KEY(`episodeId`) REFERENCES `episodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_queue_items_episodeId` ON `queue_items` (`episodeId`)",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '33d238f088c2d301998f86e0fcbf9f9d')",
    )

    @Before
    fun setUp() {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            v1Schema.forEach(db::execSQL)
            db.execSQL("INSERT INTO podcasts VALUES (1, 'https://a.example/feed', 'Show A', 'Author', 'Desc', 'https://a.example/a.jpg', 1, 111)")
            db.execSQL("INSERT INTO podcasts VALUES (2, 'https://b.example/feed', 'Show B', NULL, NULL, NULL, 0, 222)")
            db.execSQL("INSERT INTO episodes VALUES (10, 1, 'a1', 'A one', NULL, 1000, 'https://a/1.mp3', 60000, '/files/10.mp3', 'DOWNLOADED', 100, 0, 0, NULL)")
            db.execSQL("INSERT INTO episodes VALUES (11, 1, 'a2', 'A two', NULL, 2000, 'https://a/2.mp3', 60000, NULL, 'NONE', 0, 4242, 0, 99)")
            db.execSQL("INSERT INTO episodes VALUES (20, 2, 'b1', 'B one', NULL, 3000, 'https://b/1.mp3', 0, NULL, 'NONE', 0, 0, 1, 77)")
            db.execSQL("INSERT INTO queue_items VALUES (1, 11, 0)")
            db.execSQL("INSERT INTO queue_items VALUES (2, 20, 1)")
            db.version = 1
        }
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun migratesVersion1ToCurrentKeepingAllData() = runBlocking {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            // Room validates the migrated schema against the entities when the database is first opened.
            val podcasts = db.podcastDao().getAll()
            assertEquals(listOf("Show A", "Show B"), podcasts.map { it.title })
            assertEquals(true, podcasts.first().keepEpisodes)

            val feedA = db.feedDao().getForPodcast(1).single()
            assertEquals("https://a.example/feed", feedA.url)
            assertEquals("Show A", feedA.title)
            assertEquals("https://a.example/a.jpg", feedA.imageUrl)
            assertEquals(true, feedA.preferred)
            assertEquals(111L, feedA.lastRefreshedAt)
            val feedB = db.feedDao().getByUrl("https://b.example/feed")!!
            assertEquals(2L, feedB.podcastId)

            val episodesA = db.episodeDao().getForPodcast(1).sortedBy { it.id }
            assertEquals(listOf(feedA.id, feedA.id), episodesA.map { it.feedId })
            assertEquals(DownloadState.DOWNLOADED, episodesA[0].downloadState)
            assertEquals("/files/10.mp3", episodesA[0].localFilePath)
            assertEquals(4242L, episodesA[1].positionMs)
            assertEquals(99L, episodesA[1].lastPlayedAt)
            assertNull(episodesA[1].duplicateOfId)
            val b1 = db.episodeDao().getByGuid(feedB.id, "b1")!!
            assertEquals(true, b1.played)

            assertEquals(listOf(11L, 20L), db.queueDao().episodeIds())

            // Version 3: downloads made before the upgrade have no timestamp; new ones record it.
            assertNull(episodesA[0].downloadedAt)
            db.episodeDao().setDownloaded(11, "/files/11.mp3", now = 5555)
            assertEquals(5555L, db.episodeDao().get(11)!!.downloadedAt)
            db.episodeDao().clearDownload(11)
            assertNull(db.episodeDao().get(11)!!.downloadedAt)

            // The new uniqueness rule is per feed: the same guid may now exist in two feeds of one podcast.
            val extra = db.feedDao().insert(Feed(podcastId = 1, url = "https://paid.example/feed"))
            val ids = db.episodeDao().insertAll(
                listOf(Episode(podcastId = 1, feedId = extra, guid = "a1", title = "A one", audioUrl = "https://p/1.mp3")),
            )
            assertEquals(1, ids.count { it > 0 })
        } finally {
            db.close()
        }
    }
}
