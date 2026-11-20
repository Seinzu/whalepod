package dev.whalepod.data

import dev.whalepod.data.db.DownloadState
import dev.whalepod.data.db.Episode
import dev.whalepod.data.feed.ParsedEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeMergeTest {

    private fun parsed(guid: String, title: String = guid, pubDate: Long = 1, duration: Long = 0) =
        ParsedEpisode(guid, title, null, pubDate, "https://x/$guid.mp3", duration)

    @Test
    fun newItemsAreInserted() {
        val result = mergeEpisodes(7, emptyList(), listOf(parsed("a"), parsed("b"), parsed("a")))
        assertEquals(listOf("a", "b"), result.toInsert.map { it.guid })
        assertTrue(result.toInsert.all { it.podcastId == 7L && it.positionMs == 0L && !it.played })
        assertTrue(result.toUpdate.isEmpty())
    }

    @Test
    fun refreshUpdatesMetadataButKeepsUserState() {
        val existing = Episode(
            id = 1, podcastId = 7, guid = "a", title = "Old title", pubDate = 5, audioUrl = "https://x/a.mp3",
            durationMs = 1000, localFilePath = "/files/1.mp3", downloadState = DownloadState.DOWNLOADED,
            positionMs = 4242, played = true, lastPlayedAt = 99,
        )
        val result = mergeEpisodes(7, listOf(existing), listOf(parsed("a", title = "New title", pubDate = 0)))
        val updated = result.toUpdate.single()
        assertEquals("New title", updated.title)
        assertEquals(5L, updated.pubDate) // unparseable date doesn't wipe the old one
        assertEquals(1000L, updated.durationMs) // unknown duration doesn't wipe the old one
        assertEquals(4242L, updated.positionMs)
        assertEquals(true, updated.played)
        assertEquals("/files/1.mp3", updated.localFilePath)
        assertEquals(DownloadState.DOWNLOADED, updated.downloadState)
        assertEquals(99L, updated.lastPlayedAt)
        assertTrue(result.toInsert.isEmpty())
    }

    @Test
    fun unchangedEpisodesAreNotRewrittenAndMissingOnesAreKept() {
        val a = Episode(id = 1, podcastId = 7, guid = "a", title = "a", pubDate = 1, audioUrl = "https://x/a.mp3")
        val gone = Episode(id = 2, podcastId = 7, guid = "gone", title = "gone", audioUrl = "https://x/gone.mp3")
        val result = mergeEpisodes(7, listOf(a, gone), listOf(parsed("a")))
        assertTrue(result.toUpdate.isEmpty())
        assertTrue(result.toInsert.isEmpty())
    }
}
