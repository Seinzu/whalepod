package dev.whalepod.data.backup

import dev.whalepod.data.db.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class BackupTest {

    private val sample = BackupFile(
        format = BackupFile.FORMAT,
        exportedAt = 123,
        podcasts = listOf(
            BackupPodcast(
                feedUrl = "https://example.com/feed",
                title = "Show",
                keepEpisodes = true,
                episodes = listOf(
                    BackupEpisode(guid = "a", title = "A", audioUrl = "https://x/a.mp3", positionMs = 5000, lastPlayedAt = 99),
                    BackupEpisode(guid = "b", title = "B", audioUrl = "https://x/b.mp3", played = true),
                ),
            ),
        ),
        queue = listOf(BackupQueueItem("https://example.com/feed", "a")),
    )

    @Test
    fun jsonRoundTrips() {
        val json = BackupJson.encode(sample)
        assertEquals(sample, BackupJson.decode(json))
    }

    @Test
    fun readsVersion1FilesWithoutFeeds() {
        val json = """{"format":"whalepod-backup","version":1,"podcasts":[{"feedUrl":"https://e/f","title":"T",
            "episodes":[{"guid":"g","title":"E","audioUrl":"https://e/a.mp3","played":true}]}]}"""
        val podcast = BackupJson.decode(json).podcasts.single()
        assertEquals(emptyList<BackupFeed>(), podcast.feeds)
        assertEquals(null, podcast.episodes.single().feedUrl)
    }

    @Test
    fun ignoresUnknownFieldsFromLaterVersionsOfTheSameFormat() {
        val json = """{"format":"whalepod-backup","version":1,"somethingNew":true,"podcasts":[]}"""
        assertEquals(emptyList<BackupPodcast>(), BackupJson.decode(json).podcasts)
    }

    @Test
    fun rejectsOtherJson() {
        assertThrows(IOException::class.java) { BackupJson.decode("""{"hello":"world"}""") }
        assertThrows(IOException::class.java) { BackupJson.decode("""{"format":"whalepod-backup","version":3}""") }
        assertThrows(IOException::class.java) { BackupJson.decode("{ not json") }
    }

    private val stored = Episode(id = 1, podcastId = 1, feedId = 1, guid = "a", title = "A", audioUrl = "https://x/a.mp3")
    private val fromBackup = BackupEpisode(guid = "a", title = "A", audioUrl = "https://x/a.mp3")

    @Test
    fun backupStateFillsUntouchedEpisodes() {
        val merged = restoreListeningState(stored, fromBackup.copy(played = true))
        assertEquals(true, merged.played)
        val resumed = restoreListeningState(stored, fromBackup.copy(positionMs = 4000))
        assertEquals(4000L, resumed.positionMs)
    }

    @Test
    fun moreRecentListeningWins() {
        val local = stored.copy(positionMs = 1000, lastPlayedAt = 50)
        val newer = restoreListeningState(local, fromBackup.copy(positionMs = 9000, lastPlayedAt = 60))
        assertEquals(9000L, newer.positionMs)
        assertEquals(60L, newer.lastPlayedAt)

        assertSame(local, restoreListeningState(local, fromBackup.copy(positionMs = 9000, lastPlayedAt = 40)))
    }

    @Test
    fun emptyBackupStateNeverWipesLocalProgress() {
        val local = stored.copy(positionMs = 1000)
        assertSame(local, restoreListeningState(local, fromBackup))
    }

    @Test
    fun convertsToAndFromEpisodes() {
        val episode = fromBackup.copy(positionMs = 10, played = false, lastPlayedAt = 7, durationMs = 100).toEpisode(42, 9)
        assertEquals(42L, episode.podcastId)
        assertEquals(9L, episode.feedId)
        assertEquals(10L, episode.positionMs)
        assertEquals(BackupEpisode.from(episode), fromBackup.copy(positionMs = 10, lastPlayedAt = 7, durationMs = 100))
    }
}
