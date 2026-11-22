package dev.whalepod.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dev.whalepod.data.PodcastRepository
import dev.whalepod.data.db.AppDatabase
import dev.whalepod.data.db.Episode
import dev.whalepod.data.db.Feed
import dev.whalepod.data.db.Podcast
import dev.whalepod.data.db.QueueItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface ImportResult {
    data class Opml(val added: Int, val alreadySubscribed: Int, val failed: Int) : ImportResult
    data class Backup(val podcasts: Int, val newPodcastIds: List<Long>, val episodesRestored: Int) : ImportResult
}

/** Exports and imports subscriptions (OPML) and full backups (JSON) through the system file picker. */
class BackupManager(
    context: Context,
    private val db: AppDatabase,
    private val repository: PodcastRepository,
) {
    private val resolver = context.contentResolver
    private val podcastDao = db.podcastDao()
    private val feedDao = db.feedDao()
    private val episodeDao = db.episodeDao()
    private val queueDao = db.queueDao()

    /**
     * Writes an OPML file listing every feed. Combined podcasts appear once per feed, since other apps
     * have no way to combine them. Returns the number of podcasts written.
     */
    suspend fun exportOpml(uri: Uri): Int {
        val podcasts = podcastDao.getAll()
        val byId = podcasts.associateBy { it.id }
        val feeds = feedDao.getAll().mapNotNull { feed ->
            val podcast = byId[feed.podcastId] ?: return@mapNotNull null
            val title = if (feed.url == podcast.feedUrl) podcast.title else feed.title ?: podcast.title
            OpmlFeed(feed.url, title)
        }
        write(uri, Opml.write(feeds))
        return podcasts.size
    }

    /** Writes a full JSON backup. Returns the number of podcasts written. */
    suspend fun exportBackup(uri: Uri): Int {
        val podcasts = podcastDao.getAll()
        val feedUrls = feedDao.getAll().associate { it.id to it.url }
        val queue = queueDao.episodeIds().mapNotNull { id ->
            val episode = episodeDao.get(id) ?: return@mapNotNull null
            BackupQueueItem(feedUrls[episode.feedId] ?: return@mapNotNull null, episode.guid)
        }
        val backup = BackupFile(
            format = BackupFile.FORMAT,
            exportedAt = System.currentTimeMillis(),
            podcasts = podcasts.map { p ->
                BackupPodcast(
                    feedUrl = p.feedUrl,
                    title = p.title,
                    author = p.author,
                    description = p.description,
                    imageUrl = p.imageUrl,
                    keepEpisodes = p.keepEpisodes,
                    feeds = feedDao.getForPodcast(p.id).map(BackupFeed::from),
                    // Hidden duplicates are included too: they carry listening history.
                    episodes = episodeDao.getForPodcast(p.id).sortedByDescending { it.pubDate }.map { e ->
                        BackupEpisode.from(e, feedUrl = feedUrls[e.feedId]?.takeIf { it != p.feedUrl })
                    },
                )
            },
            queue = queue,
        )
        write(uri, BackupJson.encode(backup))
        return podcasts.size
    }

    /** Imports either an OPML file or a full backup, detected from the file's contents. */
    suspend fun import(uri: Uri): ImportResult {
        val text = withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IOException("Couldn't open that file")
        }.trimStart('﻿', ' ', '\t', '\r', '\n')
        return if (text.startsWith("{")) restore(BackupJson.decode(text)) else importOpml(Opml.parse(text))
    }

    private suspend fun importOpml(feeds: List<OpmlFeed>): ImportResult.Opml {
        if (feeds.isEmpty()) throw IOException("No podcasts found in that file")
        var added = 0
        var existing = 0
        var failed = 0
        for (feed in feeds) {
            if (feedDao.getByUrl(PodcastRepository.normaliseFeedUrl(feed.url)) != null) {
                existing++
                continue
            }
            runCatching { repository.subscribe(feed.url) }
                .onSuccess { added++ }
                .onFailure { failed++ }
        }
        return ImportResult.Opml(added, existing, failed)
    }

    /**
     * Restores a backup without needing the network: podcasts, their feeds and episodes are recreated from
     * the file, and listening state is merged into anything already present. Subscriptions that the backup
     * had combined are combined again. Queued episodes are appended to the queue.
     */
    private suspend fun restore(backup: BackupFile): ImportResult.Backup {
        val newPodcastIds = mutableListOf<Long>()
        val touched = linkedSetOf<Long>()
        var restored = 0
        db.withTransaction {
            for (bp in backup.podcasts) {
                val primaryUrl = PodcastRepository.normaliseFeedUrl(bp.feedUrl)
                val feeds = bp.feeds
                    .map { it.copy(url = PodcastRepository.normaliseFeedUrl(it.url)) }
                    .ifEmpty { listOf(BackupFeed(primaryUrl, bp.title, bp.author, bp.description, bp.imageUrl, true)) }
                    .let { list -> if (list.any { it.url == primaryUrl }) list else list + BackupFeed(primaryUrl, bp.title) }

                // Use the podcast that already owns the original feed, else one owning any of the feeds, else a new one.
                val owners = feeds.mapNotNull { feedDao.getByUrl(it.url)?.podcastId }.distinct()
                val podcastId = feedDao.getByUrl(primaryUrl)?.podcastId ?: owners.firstOrNull() ?: podcastDao.insert(
                    Podcast(
                        feedUrl = primaryUrl,
                        title = bp.title,
                        author = bp.author,
                        description = bp.description,
                        imageUrl = bp.imageUrl,
                        keepEpisodes = bp.keepEpisodes,
                    ),
                ).also { newPodcastIds += it }
                touched += podcastId

                // Combine any other subscriptions that hold these feeds, as the backup did.
                for (other in owners - podcastId) {
                    feedDao.movePodcastFeeds(other, podcastId)
                    episodeDao.movePodcastEpisodes(other, podcastId)
                    podcastDao.delete(other)
                    touched -= other
                }
                for (f in feeds) {
                    if (feedDao.getByUrl(f.url) == null) {
                        feedDao.insert(
                            Feed(
                                podcastId = podcastId,
                                url = f.url,
                                title = f.title,
                                author = f.author,
                                description = f.description,
                                imageUrl = f.imageUrl,
                            ),
                        )
                    }
                }
                val feedIds = feedDao.getForPodcast(podcastId).associate { it.url to it.id }
                val preferredId = feeds.firstOrNull { it.preferred }?.url?.let(feedIds::get) ?: feedIds[primaryUrl]
                preferredId?.let { feedDao.setPreferred(podcastId, it) }

                val stored = episodeDao.getForPodcast(podcastId).associateBy { it.feedId to it.guid }
                val toInsert = mutableListOf<Episode>()
                val toUpdate = mutableListOf<Episode>()
                for (be in bp.episodes) {
                    val feedId = feedIds[be.feedUrl?.let(PodcastRepository::normaliseFeedUrl) ?: primaryUrl]
                        ?: feedIds.getValue(primaryUrl)
                    val existing = stored[feedId to be.guid]
                    if (existing == null) {
                        toInsert += be.toEpisode(podcastId, feedId)
                        if (be.hasListeningState) restored++
                    } else {
                        val merged = restoreListeningState(existing, be)
                        if (merged != existing) {
                            toUpdate += merged
                            restored++
                        }
                    }
                }
                episodeDao.insertAll(toInsert.distinctBy { it.feedId to it.guid })
                episodeDao.updateAll(toUpdate)
            }

            var order = queueDao.maxSortOrder()
            for (item in backup.queue) {
                val feed = feedDao.getByUrl(PodcastRepository.normaliseFeedUrl(item.feedUrl)) ?: continue
                val episode = episodeDao.getByGuid(feed.id, item.guid) ?: continue
                if (queueDao.insert(QueueItem(episodeId = episode.id, sortOrder = order + 1)) > 0) order++
            }
        }
        touched.forEach { repository.reconcileDuplicates(it) }
        return ImportResult.Backup(backup.podcasts.size, newPodcastIds, restored)
    }

    private suspend fun write(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        // "wt" truncates, so overwriting an existing file doesn't leave stale bytes at the end.
        val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("Couldn't write that file")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
