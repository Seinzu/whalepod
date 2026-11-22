package dev.whalepod.data

import androidx.room.withTransaction
import dev.whalepod.data.db.AppDatabase
import dev.whalepod.data.db.DownloadState
import dev.whalepod.data.db.Episode
import dev.whalepod.data.db.Feed
import dev.whalepod.data.db.Podcast
import dev.whalepod.data.feed.ParsedFeed
import dev.whalepod.data.feed.RssParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class PodcastRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    private val downloader: EpisodeDownloader,
) {
    private val podcastDao = db.podcastDao()
    private val feedDao = db.feedDao()
    private val episodeDao = db.episodeDao()
    private val queueDao = db.queueDao()

    fun observePodcasts(): Flow<List<Podcast>> = podcastDao.observeAll()
    fun observePodcast(id: Long): Flow<Podcast?> = podcastDao.observe(id)
    fun observeFeeds(podcastId: Long): Flow<List<Feed>> = feedDao.observeForPodcast(podcastId)

    /** The podcast's episodes from all its feeds, with duplicates hidden. */
    fun observeEpisodes(podcastId: Long): Flow<List<Episode>> = episodeDao.observeForPodcast(podcastId)

    /**
     * Subscribes to a feed and auto-downloads its newest episode. Returns the podcast id. If the feed is
     * already subscribed (on its own or as part of a combined podcast), returns that podcast instead.
     */
    suspend fun subscribe(rawUrl: String): Long {
        val url = normaliseFeedUrl(rawUrl)
        feedDao.getByUrl(url)?.let { return it.podcastId }
        val parsed = fetchFeed(url)
        val now = System.currentTimeMillis()
        val (podcastId, feedId) = db.withTransaction {
            val podcastId = podcastDao.insert(
                Podcast(
                    feedUrl = url,
                    title = parsed.title,
                    author = parsed.author,
                    description = parsed.description,
                    imageUrl = parsed.imageUrl,
                    lastRefreshedAt = now,
                ),
            )
            val feedId = feedDao.insert(parsed.toFeed(podcastId, url, preferred = true, now))
            episodeDao.insertAll(mergeEpisodes(podcastId, feedId, emptyList(), parsed.episodes).toInsert)
            podcastId to feedId
        }
        downloadNewest(feedId)
        return podcastId
    }

    /**
     * Adds another feed (e.g. a private paid feed) to a podcast and makes it the preferred one. If the URL
     * already belongs to another subscription, that podcast is merged in instead.
     */
    suspend fun addFeed(podcastId: Long, rawUrl: String) {
        val url = normaliseFeedUrl(rawUrl)
        feedDao.getByUrl(url)?.let { existing ->
            if (existing.podcastId == podcastId) throw IOException("That feed is already part of this podcast")
            mergePodcasts(podcastId, existing.podcastId)
            return
        }
        val parsed = fetchFeed(url)
        val feedId = db.withTransaction {
            val feedId = feedDao.insert(parsed.toFeed(podcastId, url, preferred = false, System.currentTimeMillis()))
            feedDao.setPreferred(podcastId, feedId)
            episodeDao.insertAll(mergeEpisodes(podcastId, feedId, emptyList(), parsed.episodes).toInsert)
            feedId
        }
        reconcileDuplicates(podcastId)
        downloadNewest(feedId)
    }

    /**
     * Combines another subscription into [targetId]: its feeds, episodes and listening history move across
     * and its library entry disappears. The merged-in feed becomes the preferred one.
     */
    suspend fun mergePodcasts(targetId: Long, sourceId: Long) {
        if (targetId == sourceId) return
        db.withTransaction {
            val sourceFeeds = feedDao.getForPodcast(sourceId)
            feedDao.movePodcastFeeds(sourceId, targetId)
            episodeDao.movePodcastEpisodes(sourceId, targetId)
            podcastDao.delete(sourceId)
            (sourceFeeds.firstOrNull { it.preferred } ?: sourceFeeds.firstOrNull())?.let {
                feedDao.setPreferred(targetId, it.id)
            }
        }
        reconcileDuplicates(targetId)
    }

    /** Chooses which feed's copy is shown when an episode is in several feeds. */
    suspend fun setPreferredFeed(podcastId: Long, feedId: Long) {
        feedDao.setPreferred(podcastId, feedId)
        reconcileDuplicates(podcastId)
    }

    /**
     * Splits an added feed back out into its own podcast, taking its episodes and listening history with
     * it. The original feed can't be split off — it's what the podcast is named after. Returns the new
     * podcast's id.
     */
    suspend fun splitFeed(podcastId: Long, feedId: Long): Long {
        val podcast = podcastDao.get(podcastId) ?: throw IOException("Podcast not found")
        val feed = feedDao.get(feedId)?.takeIf { it.podcastId == podcastId } ?: throw IOException("Feed not found")
        if (feed.url == podcast.feedUrl) throw IOException("The original feed can't be split off")
        val newId = db.withTransaction {
            val newId = podcastDao.insert(
                Podcast(
                    feedUrl = feed.url,
                    title = feed.title ?: podcast.title,
                    author = feed.author,
                    description = feed.description,
                    imageUrl = feed.imageUrl ?: podcast.imageUrl,
                    keepEpisodes = podcast.keepEpisodes,
                    lastRefreshedAt = feed.lastRefreshedAt,
                ),
            )
            feedDao.moveFeed(feedId, newId)
            feedDao.setPreferred(newId, feedId)
            episodeDao.moveFeedEpisodes(feedId, newId)
            val remaining = feedDao.getForPodcast(podcastId)
            if (remaining.none { it.preferred }) {
                (remaining.firstOrNull { it.url == podcast.feedUrl } ?: remaining.firstOrNull())?.let {
                    feedDao.setPreferred(podcastId, it.id)
                }
            }
            newId
        }
        reconcileDuplicates(podcastId) // un-hides episodes that were duplicates of the split-off feed
        return newId
    }

    /** Re-reads every feed of a podcast. New episodes newer than anything already known are auto-downloaded. */
    suspend fun refresh(podcastId: Long) {
        val podcast = podcastDao.get(podcastId) ?: return
        val newIds = mutableListOf<Long>()
        var failure: Exception? = null
        for (feed in feedDao.getForPodcast(podcastId)) {
            val parsed = try {
                fetchFeed(feed.url)
            } catch (e: Exception) {
                failure = failure ?: e
                continue
            }
            newIds += db.withTransaction {
                val now = System.currentTimeMillis()
                feedDao.update(
                    parsed.toFeed(podcastId, feed.url, feed.preferred, now)
                        .copy(id = feed.id, imageUrl = parsed.imageUrl ?: feed.imageUrl),
                )
                if (feed.url == podcast.feedUrl) {
                    podcastDao.updateMetadata(
                        podcastId, parsed.title, parsed.author, parsed.description,
                        parsed.imageUrl ?: podcast.imageUrl, now,
                    )
                }
                val existing = episodeDao.getForFeed(feed.id)
                val newestKnown = existing.maxOfOrNull { it.pubDate } ?: 0L
                val merge = mergeEpisodes(podcastId, feed.id, existing, parsed.episodes)
                episodeDao.updateAll(merge.toUpdate)
                val ids = episodeDao.insertAll(merge.toInsert)
                val inserted = merge.toInsert.zip(ids).filter { (_, id) -> id > 0 }
                if (existing.isEmpty()) {
                    // Nothing known yet: behave like a fresh subscription and only fetch the newest.
                    listOfNotNull(inserted.maxByOrNull { it.first.pubDate }?.second)
                } else {
                    inserted.filter { (episode, _) -> episode.pubDate >= newestKnown }.map { it.second }
                }
            }
        }
        reconcileDuplicates(podcastId)
        newIds.filter { episodeDao.get(it)?.duplicateOfId == null }.forEach { downloader.enqueue(it, wifiOnly = true) }
        failure?.let { throw it }
    }

    /** Refreshes every podcast; returns the number that failed. */
    suspend fun refreshAll(): Int =
        podcastDao.getAll().count { runCatching { refresh(it.id) }.isFailure }

    /** Deletes a podcast, its feeds, episode records, queue entries and all its downloaded files. */
    suspend fun unsubscribe(podcastId: Long) {
        episodeDao.getForPodcast(podcastId).forEach { downloader.delete(it) }
        podcastDao.delete(podcastId) // feeds, episodes and queue items cascade
    }

    suspend fun setKeepEpisodes(podcastId: Long, keep: Boolean) = podcastDao.setKeepEpisodes(podcastId, keep)

    /**
     * Marks an episode played/unplayed. Marking played resets the resume point and — unless the podcast
     * keeps episodes — deletes the downloaded file. Marking unplayed keeps the resume point.
     */
    suspend fun setPlayed(episodeId: Long, played: Boolean) {
        val episode = episodeDao.get(episodeId) ?: return
        episodeDao.setPlayed(episodeId, played, if (played) 0 else episode.positionMs)
        if (played) {
            val keep = podcastDao.get(episode.podcastId)?.keepEpisodes ?: false
            if (!keep) downloader.delete(episode)
        }
    }

    suspend fun download(episodeId: Long) = downloader.enqueue(episodeId, wifiOnly = false)

    suspend fun deleteDownload(episodeId: Long) {
        episodeDao.get(episodeId)?.let { downloader.delete(it) }
    }

    /**
     * Recomputes which episodes are hidden duplicates of a copy in a more preferred feed (see
     * [findDuplicates]). When an episode newly becomes a duplicate, its listening history moves to the
     * copy that's shown, its queue entry is swapped for that copy, and if it had been downloaded its file
     * is deleted and the shown copy is downloaded instead (on Wi-Fi).
     */
    suspend fun reconcileDuplicates(podcastId: Long) {
        val podcast = podcastDao.get(podcastId) ?: return
        val newlyHidden = mutableListOf<Pair<Episode, Episode>>() // duplicate to the copy kept
        db.withTransaction {
            val feedOrder = feedDao.getForPodcast(podcastId)
                .sortedWith(compareBy<Feed>({ !it.preferred }, { it.url != podcast.feedUrl }, { it.id }))
                .map { it.id }
            val episodes = episodeDao.getForPodcast(podcastId)
            val current = episodes.associateBy { it.id }.toMutableMap()
            val duplicates = findDuplicates(episodes, feedOrder)
            for (episode in episodes) {
                val keptId = duplicates[episode.id]
                if (episode.duplicateOfId == keptId) continue
                episodeDao.setDuplicateOf(episode.id, keptId)
                if (keptId == null || episode.duplicateOfId != null) continue
                val kept = current.getValue(keptId)
                val merged = mergeListeningState(kept, episode.positionMs, episode.played, episode.lastPlayedAt)
                if (merged != kept) {
                    episodeDao.setListeningState(kept.id, merged.positionMs, merged.played, merged.lastPlayedAt)
                    current[keptId] = merged
                }
                queueDao.replaceEpisode(episode.id, keptId)
                queueDao.remove(episode.id)
                newlyHidden += episode to merged
            }
        }
        for ((hidden, kept) in newlyHidden) {
            if (hidden.downloadState == DownloadState.NONE) continue
            val wantedOffline = hidden.downloadState != DownloadState.FAILED
            downloader.delete(hidden)
            val keptHasFile = kept.downloadState != DownloadState.NONE && kept.downloadState != DownloadState.FAILED
            if (wantedOffline && !keptHasFile && !kept.played) downloader.enqueue(kept.id, wifiOnly = true)
        }
    }

    private suspend fun downloadNewest(feedId: Long) {
        episodeDao.getForFeed(feedId)
            .filter { it.duplicateOfId == null && !it.played }
            .maxByOrNull { it.pubDate }
            ?.let { downloader.enqueue(it.id, wifiOnly = true) }
    }

    private fun ParsedFeed.toFeed(podcastId: Long, url: String, preferred: Boolean, now: Long) = Feed(
        podcastId = podcastId,
        url = url,
        title = title,
        author = author,
        description = description,
        imageUrl = imageUrl,
        preferred = preferred,
        lastRefreshedAt = now,
    )

    private suspend fun fetchFeed(url: String): ParsedFeed = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Couldn't fetch feed (HTTP ${response.code})")
            val body = response.body ?: throw IOException("Empty response")
            body.byteStream().use { RssParser.parse(it) }
        }
    }

    companion object {
        fun normaliseFeedUrl(raw: String): String {
            var url = raw.trim()
            if (url.startsWith("feed://", ignoreCase = true)) url = "https://" + url.substring(7)
            if (!url.contains("://")) url = "https://$url"
            return url
        }
    }
}
