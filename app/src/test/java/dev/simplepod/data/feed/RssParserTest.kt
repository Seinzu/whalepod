package dev.simplepod.data.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.time.ZonedDateTime

class RssParserTest {

    private fun parseResource(name: String) =
        javaClass.classLoader!!.getResourceAsStream(name).use { RssParser.parse(it, KXmlParser()) }

    @Test
    fun parsesChannelMetadata() {
        val feed = parseResource("sample_feed.xml")
        assertEquals("The Example Show", feed.title)
        assertEquals("Example Media", feed.author)
        assertEquals("A show about <b>examples</b>.", feed.description)
        // iTunes image is preferred over the RSS <image>.
        assertEquals("https://example.com/itunes-image.jpg", feed.imageUrl)
    }

    @Test
    fun parsesEpisodesSkippingItemsWithoutAudioAndDuplicates() {
        val feed = parseResource("sample_feed.xml")
        assertEquals(listOf("ep-3", "https://cdn.example.com/ep2.m4a", "ep-1"), feed.episodes.map { it.guid })

        val ep3 = feed.episodes[0]
        assertEquals("Episode 3 & friends", ep3.title)
        assertEquals("Third one", ep3.description)
        assertEquals("https://cdn.example.com/ep3.mp3?x=1", ep3.audioUrl)
        assertEquals((3600 + 2 * 60 + 3) * 1000L, ep3.durationMs)
        assertEquals(ZonedDateTime.parse("2025-01-15T10:00:00Z").toInstant().toEpochMilli(), ep3.pubDate)

        val ep2 = feed.episodes[1]
        assertEquals("Second, no guid", ep2.description) // falls back to itunes:summary
        assertEquals((45 * 60 + 30) * 1000L, ep2.durationMs)
        assertEquals(ZonedDateTime.parse("2025-01-07T13:30:00Z").toInstant().toEpochMilli(), ep2.pubDate)

        val ep1 = feed.episodes[2]
        assertEquals("Episode 1", ep1.title)
        assertEquals(3_600_000L, ep1.durationMs)
    }

    @Test
    fun fallsBackToRssImageAndUrlGuid() {
        val feed = parseResource("minimal_feed.xml")
        assertEquals("https://example.com/only-rss.png", feed.imageUrl)
        assertNull(feed.author)
        val ep = feed.episodes.single()
        assertEquals("http://example.com/only.mp3", ep.guid)
        assertEquals(0L, ep.durationMs)
        assertEquals(ZonedDateTime.parse("2025-03-01T17:00:00Z").toInstant().toEpochMilli(), ep.pubDate)
    }

    @Test
    fun rejectsNonRss() {
        assertThrows(FeedParseException::class.java) {
            RssParser.parse(ByteArrayInputStream("<html><body>hi</body></html>".toByteArray()), KXmlParser())
        }
        assertThrows(FeedParseException::class.java) {
            RssParser.parse(ByteArrayInputStream("not xml at all".toByteArray()), KXmlParser())
        }
    }

    @Test
    fun parsesDurations() {
        assertEquals(0L, RssParser.parseDuration(null))
        assertEquals(0L, RssParser.parseDuration(""))
        assertEquals(0L, RssParser.parseDuration("abc"))
        assertEquals(90_000L, RssParser.parseDuration("90"))
        assertEquals(90_500L, RssParser.parseDuration("90.5"))
        assertEquals(330_000L, RssParser.parseDuration("5:30"))
        assertEquals(3_723_000L, RssParser.parseDuration("01:02:03"))
    }

    @Test
    fun parsesDates() {
        val expected = ZonedDateTime.parse("2024-06-04T15:00:00Z").toInstant().toEpochMilli()
        assertEquals(expected, RssParser.parseDate("Tue, 04 Jun 2024 15:00:00 GMT"))
        assertEquals(expected, RssParser.parseDate("Tue, 4 Jun 2024 15:00:00 +0000"))
        assertEquals(expected, RssParser.parseDate("Tue, 04 Jun 2024 11:00:00 EDT"))
        assertEquals(expected, RssParser.parseDate("04 Jun 2024 15:00:00 +0000"))
        assertEquals(expected, RssParser.parseDate("2024-06-04T15:00:00Z"))
        assertEquals(0L, RssParser.parseDate("whenever"))
        assertEquals(0L, RssParser.parseDate(null))
    }
}
