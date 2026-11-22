package dev.whalepod.data

import dev.whalepod.data.db.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DuplicatesTest {
    private val day = 24L * 60 * 60 * 1000
    private var nextId = 1L

    private fun ep(feed: Long, title: String, daysAgo: Int, guid: String = "g${nextId}") =
        Episode(
            id = nextId++, podcastId = 1, feedId = feed, guid = guid, title = title,
            pubDate = 1_000 * day - daysAgo * day, audioUrl = "https://x/$guid.mp3",
        )

    @Test
    fun normalisesPaidMarkersButKeepsOtherBrackets() {
        assertEquals("episode 12 the big one", normaliseTitle("Episode 12: The Big One (Ad-Free)"))
        assertEquals("episode 12 the big one", normaliseTitle("Episode 12 – The Big One [Patreon Early Access]"))
        assertEquals("episode 12 the big one", normaliseTitle("EPISODE 12: The BIG one - Ad Free"))
        assertEquals("episode 12 part 2", normaliseTitle("Episode 12 (Part 2)"))
    }

    @Test
    fun hidesFreeCopiesOfPreferredFeedEpisodes() {
        val paid = 2L
        val free = 1L
        val paidEp = ep(paid, "Episode 5 (Ad-Free)", daysAgo = 7) // a week of early access
        val paidBonus = ep(paid, "Bonus: Behind the scenes", daysAgo = 3)
        val freeEp = ep(free, "Episode 5", daysAgo = 0)
        val freeOnly = ep(free, "Episode 4", daysAgo = 14)

        val dupes = findDuplicates(listOf(paidEp, paidBonus, freeEp, freeOnly), feedOrder = listOf(paid, free))
        assertEquals(mapOf(freeEp.id to paidEp.id), dupes)
    }

    @Test
    fun preferenceDecidesWhichCopyIsKept() {
        val a = ep(1, "Same", daysAgo = 1)
        val b = ep(2, "Same", daysAgo = 1)
        assertEquals(mapOf(b.id to a.id), findDuplicates(listOf(a, b), listOf(1, 2)))
        assertEquals(mapOf(a.id to b.id), findDuplicates(listOf(a, b), listOf(2, 1)))
    }

    @Test
    fun sameGuidMatchesRegardlessOfTitleOrDate() {
        val a = ep(1, "Original title", daysAgo = 100, guid = "shared")
        val b = ep(2, "Renamed in the other feed", daysAgo = 0, guid = "shared")
        assertEquals(mapOf(b.id to a.id), findDuplicates(listOf(a, b), listOf(1, 2)))
    }

    @Test
    fun matchesAreOneToOneAndNearestInTime() {
        // A weekly "Mailbag" in both feeds: each free copy pairs with its own paid copy.
        val paidNew = ep(1, "Mailbag", daysAgo = 1)
        val paidOld = ep(1, "Mailbag", daysAgo = 8)
        val freeNew = ep(2, "Mailbag", daysAgo = 0)
        val freeOld = ep(2, "Mailbag", daysAgo = 7)
        val dupes = findDuplicates(listOf(paidNew, paidOld, freeNew, freeOld), listOf(1, 2))
        assertEquals(mapOf(freeNew.id to paidNew.id, freeOld.id to paidOld.id), dupes)
    }

    @Test
    fun leavesDistantOrUndatedTitleMatchesAndSameFeedRepeatsAlone() {
        val a = ep(1, "Trailer", daysAgo = 30)
        val b = ep(2, "Trailer", daysAgo = 0)
        val undated = ep(2, "Episode 1", daysAgo = 0).copy(pubDate = 0)
        val undatedOther = ep(1, "Episode 1", daysAgo = 0).copy(pubDate = 0)
        val repeat1 = ep(1, "Repeat", daysAgo = 0)
        val repeat2 = ep(1, "Repeat", daysAgo = 0)
        assertEquals(emptyMap<Long, Long>(), findDuplicates(listOf(a, b, undated, undatedOther, repeat1, repeat2), listOf(1, 2)))
    }

    @Test
    fun singleFeedHasNoDuplicates() {
        assertEquals(emptyMap<Long, Long>(), findDuplicates(listOf(ep(1, "A", 0), ep(1, "A", 0)), listOf(1)))
    }

    @Test
    fun listeningStateMergeRules() {
        val untouched = ep(1, "A", 0)
        assertEquals(3000L, mergeListeningState(untouched, 3000, false, null).positionMs)
        assertEquals(true, mergeListeningState(untouched, 0, true, null).played)
        assertSame(untouched, mergeListeningState(untouched, 0, false, null))

        val listened = untouched.copy(positionMs = 1000, lastPlayedAt = 50)
        assertEquals(9000L, mergeListeningState(listened, 9000, false, 60).positionMs)
        assertSame(listened, mergeListeningState(listened, 9000, false, 40))
    }
}
