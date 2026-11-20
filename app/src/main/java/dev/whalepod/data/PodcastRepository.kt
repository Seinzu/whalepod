package dev.whalepod.data

import androidx.room.withTransaction
import dev.whalepod.data.db.AppDatabase
import dev.whalepod.data.db.Episode
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
    private val episodeDao = db.episodeDao()

    fun observePodcasts(): Flow<List<Podcast>> = podcastDao.observeAll()
    fun observePodcast(id: Long): Flow<Podcast?> = podcastDao.observe(id)
    fun observeEpisodes(podcastId: Long): Flow<List<Episode>> = episodeDao.observeForPodcast(podcastId)

    /** Subscribes to a feed and auto-downloads its newest episode. Returns the podcast id. */
    suspend fun subscribe(rawUrl: String): Long {
        val url = normaliseFeedUrl(rawUrl)
        podcastDao.getByFeedUrl(url)?.let { return it.id }
        val feed = fetchFeed(url)
        val podcastId = db.withTransaction {
            val id = podcastDao.insert(
                Podcast(
                    feedUrl = url,
                    title = feed.title,
                    author = feed.author,
                    description = feed.description,
                    imageUrl = feed.imageUrl,
                    lastRefreshedAt = System.currentTimeMillis(),
                ),
            )
            episodeDao.insertAll(mergeEpisodes(id, emptyList(), feed.episodes).toInsert)
            id
        }
        episodeDao.getForPodcast(podcastId).maxByOrNull { it.pubDate }?.let {
            downloader.enqueue(it.id, wifiOnly = true)
        }
        return podcastId
    }

    /** Re-reads a feed. New episodes newer than anything already known are auto-downloaded on Wi-Fi. */
    suspend fun refresh(podcastId: Long) {
        val podcast = podcastDao.get(podcastId) ?: return
        val feed = fetchFeed(podcast.feedUrl)
        val newIds = db.withTransaction {
            podcastDao.update(
                podcast.copy(
                    title = feed.title,
                    author = feed.author,
                    description = feed.description,
                    imageUrl = feed.imageUrl ?: podcast.imageUrl,
                    lastRefreshedAt = System.currentTimeMillis(),
                ),
            )
            val existing = episodeDao.getForPodcast(podcastId)
            val newestKnown = existing.maxOfOrNull { it.pubDate } ?: 0L
            val merge = mergeEpisodes(podcastId, existing, feed.episodes)
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
        newIds.forEach { downloader.enqueue(it, wifiOnly = true) }
    }

    /** Refreshes every podcast; returns the number that failed. */
    suspend fun refreshAll(): Int =
        podcastDao.getAll().count { runCatching { refresh(it.id) }.isFailure }

    /** Deletes a podcast, its episode records, its queue entries and all its downloaded files. */
    suspend fun unsubscribe(podcastId: Long) {
        episodeDao.getForPodcast(podcastId).forEach { downloader.delete(it) }
        podcastDao.delete(podcastId) // episodes and queue items cascade
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
