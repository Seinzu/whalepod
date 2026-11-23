package dev.whalepod.data

import dev.whalepod.data.db.Episode
import kotlin.math.abs

/** Two copies with the same title count as the same episode if published within this window (covers early access). */
const val DUPLICATE_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

private val PAID_MARKERS = Regex(
    "ad[- ]?free|no ads|premium|patreon|supercast|subscriber|members?( only)?|early access|extended|uncut|unedited",
    RegexOption.IGNORE_CASE,
)
private val BRACKETED = Regex("""[(\[{][^)\]}]*[)\]}]""")
private val TRAILING_MARKER = Regex(
    """\s*[-–—|:]\s*(ad[- ]?free|no ads|premium|patreon|early access|extended( cut)?|uncut)\s*$""",
    RegexOption.IGNORE_CASE,
)
private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

/**
 * Normalises an episode title for duplicate matching: drops paid-feed markers such as "(Ad-Free)" or
 * " - Patreon", then case, punctuation and spacing. Other bracketed text (e.g. "(Part 2)") is kept.
 */
fun normaliseTitle(title: String): String {
    var t = BRACKETED.replace(title) { if (PAID_MARKERS.containsMatchIn(it.value)) " " else it.value }
    t = TRAILING_MARKER.replace(t, "")
    return NON_WORD.replace(t.lowercase(), " ").trim()
}

/**
 * Finds episodes that are copies of an episode in a more preferred feed of the same podcast.
 *
 * Feeds are considered in [feedOrder] (most preferred first). An episode is a duplicate of an episode
 * already kept from an earlier feed if they share a GUID, or have the same normalised title and were
 * published within [windowMs] of each other. Each kept episode absorbs at most one duplicate per feed,
 * choosing the closest publication date. Episodes in the same feed are never matched with each other.
 *
 * @return duplicate episode id → id of the episode it duplicates.
 */
fun findDuplicates(
    episodes: List<Episode>,
    feedOrder: List<Long>,
    windowMs: Long = DUPLICATE_WINDOW_MS,
): Map<Long, Long> {
    val rank = feedOrder.withIndex().associate { it.value to it.index }
    val byFeed = episodes.groupBy { it.feedId }.entries.sortedBy { rank[it.key] ?: Int.MAX_VALUE }
    if (byFeed.size < 2) return emptyMap()

    val keptByGuid = HashMap<String, Episode>()
    val keptByTitle = HashMap<String, MutableList<Episode>>()
    val result = HashMap<Long, Long>()

    for ((feedId, feedEpisodes) in byFeed) {
        val claimed = HashSet<Long>() // kept episodes already matched by this feed
        val keptFromThisFeed = mutableListOf<Episode>()
        for (e in feedEpisodes.sortedByDescending { it.pubDate }) {
            val title = normaliseTitle(e.title)
            val match = keptByGuid[e.guid]?.takeIf { it.id !in claimed }
                ?: keptByTitle[title].orEmpty()
                    .filter { it.id !in claimed && it.pubDate > 0 && e.pubDate > 0 && abs(it.pubDate - e.pubDate) <= windowMs }
                    .minByOrNull { abs(it.pubDate - e.pubDate) }
                    ?.takeIf { title.isNotEmpty() }
            if (match != null && match.feedId != feedId) {
                result[e.id] = match.id
                claimed += match.id
            } else {
                keptFromThisFeed += e
            }
        }
        for (k in keptFromThisFeed) {
            keptByGuid.putIfAbsent(k.guid, k)
            keptByTitle.getOrPut(normaliseTitle(k.title)) { mutableListOf() } += k
        }
    }
    return result
}

/**
 * Merges listening state from another copy of an episode (a backup, or a duplicate in another feed) into
 * [target]. The other copy wins if it was listened to more recently, or if [target] has never been touched;
 * otherwise [target] is returned unchanged.
 */
fun mergeListeningState(target: Episode, positionMs: Long, played: Boolean, lastPlayedAt: Long?): Episode {
    val otherHasState = played || positionMs > 0
    val otherIsNewer = lastPlayedAt != null && (target.lastPlayedAt == null || lastPlayedAt > target.lastPlayedAt)
    val targetUntouched = target.lastPlayedAt == null && !target.played && target.positionMs == 0L
    return if (otherIsNewer || (targetUntouched && otherHasState)) {
        target.copy(positionMs = positionMs, played = played, lastPlayedAt = lastPlayedAt)
    } else {
        target
    }
}
