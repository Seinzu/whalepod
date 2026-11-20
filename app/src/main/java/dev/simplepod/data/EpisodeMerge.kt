package dev.simplepod.data

import dev.simplepod.data.db.Episode
import dev.simplepod.data.feed.ParsedEpisode

data class EpisodeMergeResult(val toInsert: List<Episode>, val toUpdate: List<Episode>)

/**
 * Merges freshly-parsed feed items into the episodes already stored for a podcast.
 *
 * Feed metadata (title, description, date, URL, duration) is refreshed, but the user's own state —
 * play position, played flag, downloaded file, download state — is never touched. Stored episodes that
 * have disappeared from the feed are left alone so their record survives.
 */
fun mergeEpisodes(podcastId: Long, existing: List<Episode>, parsed: List<ParsedEpisode>): EpisodeMergeResult {
    val byGuid = existing.associateBy { it.guid }
    val toInsert = mutableListOf<Episode>()
    val toUpdate = mutableListOf<Episode>()
    for (item in parsed.distinctBy { it.guid }) {
        val current = byGuid[item.guid]
        if (current == null) {
            toInsert += Episode(
                podcastId = podcastId,
                guid = item.guid,
                title = item.title,
                description = item.description,
                pubDate = item.pubDate,
                audioUrl = item.audioUrl,
                durationMs = item.durationMs,
            )
        } else {
            val updated = current.copy(
                title = item.title,
                description = item.description,
                pubDate = if (item.pubDate > 0) item.pubDate else current.pubDate,
                audioUrl = item.audioUrl,
                durationMs = if (item.durationMs > 0) item.durationMs else current.durationMs,
            )
            if (updated != current) toUpdate += updated
        }
    }
    return EpisodeMergeResult(toInsert, toUpdate)
}
