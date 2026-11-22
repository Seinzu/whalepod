package dev.whalepod.ui

import androidx.core.text.HtmlCompat
import java.text.DateFormat
import java.util.Date

fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatDuration(ms: Long): String? {
    if (ms <= 0) return null
    val minutes = (ms + 30_000) / 60_000
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

fun formatDate(epochMs: Long): String? =
    if (epochMs <= 0) null else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))

fun htmlToText(html: String?): String =
    html?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() }.orEmpty()

/**
 * A short name for a feed within a combined podcast, e.g. "Ad-Free" for a feed titled
 * "My Show (Ad-Free)" in the podcast "My Show". Falls back to the feed's title, then its host name.
 */
fun shortFeedLabel(feedTitle: String?, podcastTitle: String, url: String): String {
    val host = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()
    val title = feedTitle?.trim().orEmpty()
    if (title.isEmpty() || title.equals(podcastTitle.trim(), ignoreCase = true)) return host ?: title.ifEmpty { url }
    if (title.startsWith(podcastTitle.trim(), ignoreCase = true)) {
        val rest = title.substring(podcastTitle.trim().length).trim { it.isWhitespace() || it in "()[]{}-–—:|·" }
        if (rest.isNotEmpty()) return rest
    }
    return title
}
