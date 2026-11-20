package dev.simplepod.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.IOException

class OpmlTest {

    @Test
    fun roundTripsFeedsIncludingSpecialCharacters() {
        val feeds = listOf(
            OpmlFeed("https://example.com/feed.xml?a=1&b=2", "Tom & Jerry's \"Show\" <live>"),
            OpmlFeed("http://other.example/rss", "Other"),
        )
        val xml = Opml.write(feeds, now = 0)
        assertTrue(xml.contains("xmlUrl=\"https://example.com/feed.xml?a=1&amp;b=2\""))
        assertTrue(xml.contains("<dateCreated>Thu, 1 Jan 1970 00:00:00 GMT</dateCreated>"))
        assertEquals(feeds, Opml.parse(xml, KXmlParser()))
    }

    @Test
    fun readsNestedOutlinesFromOtherApps() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <opml version="1.0">
              <head><title>Exported from another app</title></head>
              <body>
                <outline text="feeds">
                  <outline type="rss" text="Text Only" xmlUrl="https://a.example/feed" />
                  <outline text="Folder">
                    <outline type="rss" title="Has Title" text="ignored" XMLURL=" https://b.example/feed " />
                  </outline>
                  <outline type="rss" text="Duplicate" xmlUrl="https://a.example/feed" />
                  <outline text="Not a feed" htmlUrl="https://c.example" />
                </outline>
              </body>
            </opml>
        """.trimIndent()
        assertEquals(
            listOf(
                OpmlFeed("https://a.example/feed", "Text Only"),
                OpmlFeed("https://b.example/feed", "Has Title"),
            ),
            Opml.parse(xml, KXmlParser()),
        )
    }

    @Test
    fun rejectsFilesThatAreNotOpml() {
        assertThrows(IOException::class.java) { Opml.parse("<rss><channel/></rss>", KXmlParser()) }
        assertThrows(IOException::class.java) { Opml.parse("just some text", KXmlParser()) }
    }
}
