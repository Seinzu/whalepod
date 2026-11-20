package dev.simplepod.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dev.simplepod.data.PodcastRepository
import dev.simplepod.data.db.AppDatabase
import dev.simplepod.data.db.Episode
import dev.simplepod.data.db.Podcast
import dev.simplepod.data.db.QueueItem
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
    private val episodeDao = db.episodeDao()
    private val queueDao = db.queueDao()

    /** Writes an OPML file of all subscriptions. Returns the number of podcasts written. */
    suspend fun exportOpml(uri: Uri): Int {
        val podcasts = podcastDao.getAll()
        write(uri, Opml.write(podcasts.map { OpmlFeed(it.feedUrl, it.title) }))
        return podcasts.size
    }

    /** Writes a full JSON backup. Returns the number of podcasts written. */
    suspend fun exportBackup(uri: Uri): Int {
        val podcasts = podcastDao.getAll()
        val byId = podcasts.associateBy { it.id }
        val queue = queueDao.episodeIds().mapNotNull { id ->
            val episode = episodeDao.get(id) ?: return@mapNotNull null
            val podcast = byId[episode.podcastId] ?: return@mapNotNull null
            BackupQueueItem(podcast.feedUrl, episode.guid)
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
                    episodes = episodeDao.getForPodcast(p.id).sortedByDescending { it.pubDate }.map(BackupEpisode::from),
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
        }.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return if (text.startsWith("{")) restore(BackupJson.decode(text)) else importOpml(Opml.parse(text))
    }

    private suspend fun importOpml(feeds: List<OpmlFeed>): ImportResult.Opml {
        if (feeds.isEmpty()) throw IOException("No podcasts found in that file")
        var added = 0
        var existing = 0
        var failed = 0
        for (feed in feeds) {
            if (podcastDao.getByFeedUrl(PodcastRepository.normaliseFeedUrl(feed.url)) != null) {
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
     * Restores a backup without needing the network: podcasts and episodes are recreated from the file,
     * and listening state is merged into anything already present. Queued episodes are appended.
     */
    private suspend fun restore(backup: BackupFile): ImportResult.Backup {
        val newPodcastIds = mutableListOf<Long>()
        var restored = 0
        db.withTransaction {
            for (bp in backup.podcasts) {
                val url = PodcastRepository.normaliseFeedUrl(bp.feedUrl)
                val podcastId = podcastDao.getByFeedUrl(url)?.id ?: podcastDao.insert(
                    Podcast(
                        feedUrl = url,
                        title = bp.title,
                        author = bp.author,
                        description = bp.description,
                        imageUrl = bp.imageUrl,
                        keepEpisodes = bp.keepEpisodes,
                    ),
                ).also { newPodcastIds += it }

                val current = episodeDao.getForPodcast(podcastId).associateBy { it.guid }
                val toInsert = mutableListOf<Episode>()
                val toUpdate = mutableListOf<Episode>()
                for (be in bp.episodes.distinctBy { it.guid }) {
                    val stored = current[be.guid]
                    if (stored == null) {
                        toInsert += be.toEpisode(podcastId)
                        if (be.hasListeningState) restored++
                    } else {
                        val merged = restoreListeningState(stored, be)
                        if (merged != stored) {
                            toUpdate += merged
                            restored++
                        }
                    }
                }
                episodeDao.insertAll(toInsert)
                episodeDao.updateAll(toUpdate)
            }

            var order = queueDao.maxSortOrder()
            for (item in backup.queue) {
                val podcast = podcastDao.getByFeedUrl(PodcastRepository.normaliseFeedUrl(item.feedUrl)) ?: continue
                val episode = episodeDao.getByGuid(podcast.id, item.guid) ?: continue
                if (queueDao.insert(QueueItem(episodeId = episode.id, sortOrder = order + 1)) > 0) order++
            }
        }
        return ImportResult.Backup(backup.podcasts.size, newPodcastIds, restored)
    }

    private suspend fun write(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        // "wt" truncates, so overwriting an existing file doesn't leave stale bytes at the end.
        val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("Couldn't write that file")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
