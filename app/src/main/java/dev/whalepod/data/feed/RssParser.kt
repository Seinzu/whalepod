package dev.whalepod.data.feed

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ParsedFeed(
    val title: String,
    val author: String?,
    val description: String?,
    val imageUrl: String?,
    val episodes: List<ParsedEpisode>,
)

data class ParsedEpisode(
    val guid: String,
    val title: String,
    val description: String?,
    val pubDate: Long,
    val audioUrl: String,
    val durationMs: Long,
)

class FeedParseException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A small RSS 2.0 + iTunes-extensions parser built on [XmlPullParser]. */
object RssParser {

    fun parse(input: InputStream, parser: XmlPullParser = Xml.newPullParser()): ParsedFeed {
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(input, null)
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "channel") {
                    return readChannel(parser)
                }
            }
        } catch (e: FeedParseException) {
            throw e
        } catch (e: Exception) {
            throw FeedParseException("Couldn't read the feed: ${e.message}", e)
        }
        throw FeedParseException("That doesn't look like a podcast RSS feed")
    }

    private fun readChannel(p: XmlPullParser): ParsedFeed {
        var title: String? = null
        var author: String? = null
        var description: String? = null
        var itunesSummary: String? = null
        var itunesImage: String? = null
        var rssImage: String? = null
        val episodes = mutableListOf<ParsedEpisode>()

        forEachChild(p) { name, ns ->
            when {
                ns.isEmpty() && name == "title" -> title = readText(p)
                ns.isEmpty() && name == "description" -> description = readText(p)
                ns.isEmpty() && name == "image" -> rssImage = readImageUrl(p)
                ns.isEmpty() && name == "item" -> readItem(p)?.let(episodes::add)
                ns.isItunes() && name == "author" -> author = readText(p)
                ns.isItunes() && name == "summary" -> itunesSummary = readText(p)
                ns.isItunes() && name == "image" -> {
                    itunesImage = p.getAttributeValue(null, "href")?.trim()
                    skip(p)
                }
                else -> skip(p)
            }
        }

        return ParsedFeed(
            title = title?.takeIf { it.isNotBlank() } ?: "Untitled podcast",
            author = author?.takeIf { it.isNotBlank() },
            description = (description?.takeIf { it.isNotBlank() } ?: itunesSummary)?.takeIf { it.isNotBlank() },
            imageUrl = (itunesImage?.takeIf { it.isNotBlank() } ?: rssImage)?.takeIf { it.isNotBlank() },
            episodes = episodes.distinctBy { it.guid },
        )
    }

    private fun readImageUrl(p: XmlPullParser): String? {
        var url: String? = null
        forEachChild(p) { name, ns ->
            if (ns.isEmpty() && name == "url") url = readText(p) else skip(p)
        }
        return url
    }

    private fun readItem(p: XmlPullParser): ParsedEpisode? {
        var guid: String? = null
        var title: String? = null
        var description: String? = null
        var itunesSummary: String? = null
        var pubDate: String? = null
        var audioUrl: String? = null
        var duration: String? = null

        forEachChild(p) { name, ns ->
            when {
                ns.isEmpty() && name == "title" -> title = readText(p)
                ns.isEmpty() && name == "guid" -> guid = readText(p)
                ns.isEmpty() && name == "pubDate" -> pubDate = readText(p)
                ns.isEmpty() && name == "description" -> description = readText(p)
                ns.isEmpty() && name == "enclosure" -> {
                    // Some feeds have several enclosures; keep the first one.
                    if (audioUrl == null) audioUrl = p.getAttributeValue(null, "url")?.trim()
                    skip(p)
                }
                ns.isItunes() && name == "duration" -> duration = readText(p)
                ns.isItunes() && name == "summary" -> itunesSummary = readText(p)
                ns.isItunes() && name == "title" && title.isNullOrBlank() -> title = readText(p)
                else -> skip(p)
            }
        }

        val url = audioUrl?.takeIf { it.isNotBlank() } ?: return null // not a playable episode
        return ParsedEpisode(
            guid = guid?.takeIf { it.isNotBlank() } ?: url,
            title = title?.takeIf { it.isNotBlank() } ?: "Untitled episode",
            description = (description?.takeIf { it.isNotBlank() } ?: itunesSummary)?.takeIf { it.isNotBlank() },
            pubDate = parseDate(pubDate),
            audioUrl = url,
            durationMs = parseDuration(duration),
        )
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private fun String?.isItunes() = this != null && this.contains("itunes", ignoreCase = true)

    /** Calls [block] for every direct child START_TAG of the current element. [block] must consume the child. */
    private inline fun forEachChild(p: XmlPullParser, block: (name: String, ns: String) -> Unit) {
        val depth = p.depth
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> block(p.name, p.namespace ?: "")
                XmlPullParser.END_TAG -> if (p.depth == depth) return
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** Reads all text inside the current element (ignoring any nested markup) and leaves the parser on its END_TAG. */
    private fun readText(p: XmlPullParser): String {
        val depth = p.depth
        val sb = StringBuilder()
        while (true) {
            when (p.next()) {
                XmlPullParser.TEXT -> sb.append(p.text)
                XmlPullParser.END_TAG -> if (p.depth == depth) break
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return sb.toString().trim()
    }

    private fun skip(p: XmlPullParser) {
        if (p.eventType != XmlPullParser.START_TAG) return
        var level = 1
        while (level > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> level++
                XmlPullParser.END_TAG -> level--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** Parses `HH:MM:SS`, `MM:SS` or plain seconds (possibly fractional). Returns 0 if unknown. */
    fun parseDuration(value: String?): Long {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return 0
        val parts = v.split(":")
        if (parts.size > 3) return 0
        var seconds = 0.0
        for (part in parts) {
            val n = part.trim().toDoubleOrNull() ?: return 0
            seconds = seconds * 60 + n
        }
        return (seconds * 1000).toLong().coerceAtLeast(0)
    }

    private val legacyDateFormats = listOf(
        "EEE, d MMM yyyy HH:mm:ss zzz",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm zzz",
        "EEE, d MMM yyyy HH:mm:ss",
        "d MMM yyyy HH:mm:ss zzz",
        "d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yy HH:mm:ss zzz",
        "EEE d MMM yyyy HH:mm:ss zzz",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd",
    )

    /** Parses RSS (RFC 822) dates and a few common deviations. Returns 0 if unparseable. */
    fun parseDate(value: String?): Long {
        val v = value?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (v.isEmpty()) return 0
        runCatching {
            return ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }
        for (pattern in legacyDateFormats) {
            val format = SimpleDateFormat(pattern, Locale.US)
            val pos = ParsePosition(0)
            val date = runCatching { format.parse(v, pos) }.getOrNull()
            if (date != null && pos.index > 0) return date.time
        }
        return 0
    }
}
