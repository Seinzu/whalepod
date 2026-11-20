package dev.whalepod.data.backup

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class OpmlFeed(val url: String, val title: String?)

/** Reads and writes OPML 2.0 subscription lists — the format every podcast app can import/export. */
object Opml {

    fun write(feeds: List<OpmlFeed>, now: Long = System.currentTimeMillis()): String {
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC))
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<opml version=\"2.0\">\n")
            append("  <head>\n")
            append("    <title>Podcast subscriptions</title>\n")
            append("    <dateCreated>").append(escape(date)).append("</dateCreated>\n")
            append("  </head>\n")
            append("  <body>\n")
            for (feed in feeds) {
                val title = escape(feed.title ?: feed.url)
                append("    <outline type=\"rss\" text=\"").append(title).append("\" title=\"").append(title)
                    .append("\" xmlUrl=\"").append(escape(feed.url)).append("\" />\n")
            }
            append("  </body>\n")
            append("</opml>\n")
        }
    }

    /** Returns every outline with an `xmlUrl`, at any nesting depth. */
    fun parse(text: String, parser: XmlPullParser = Xml.newPullParser()): List<OpmlFeed> {
        val feeds = mutableListOf<OpmlFeed>()
        var sawOpml = false
        try {
            parser.setInput(StringReader(text))
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when {
                    parser.name.equals("opml", ignoreCase = true) -> sawOpml = true
                    parser.name.equals("outline", ignoreCase = true) -> {
                        val url = attribute(parser, "xmlUrl")?.trim()
                        if (!url.isNullOrEmpty()) {
                            val title = (attribute(parser, "title") ?: attribute(parser, "text"))?.trim()
                            feeds += OpmlFeed(url, title?.takeIf { it.isNotEmpty() })
                        }
                    }
                }
            }
        } catch (e: Exception) {
            throw IOException("That file isn't an OPML or backup file", e)
        }
        if (!sawOpml) throw IOException("That file isn't an OPML or backup file")
        return feeds.distinctBy { it.url }
    }

    private fun attribute(parser: XmlPullParser, name: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i).equals(name, ignoreCase = true)) return parser.getAttributeValue(i)
        }
        return null
    }

    private fun escape(s: String) = buildString(s.length) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}
