package dev.whalepod.data.backup

import dev.whalepod.data.db.Episode
import dev.whalepod.data.db.Feed
import dev.whalepod.data.mergeListeningState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * A full backup: subscriptions, every known episode with its listening state, and the queue.
 * Downloaded audio files are not included — they can be re-downloaded or streamed.
 */
@Serializable
data class BackupFile(
    /** Always [FORMAT]; required (no default) so arbitrary JSON isn't mistaken for a backup. */
    val format: String,
    val version: Int = VERSION,
    val exportedAt: Long = 0,
    val podcasts: List<BackupPodcast> = emptyList(),
    val queue: List<BackupQueueItem> = emptyList(),
) {
    companion object {
        const val FORMAT = "whalepod-backup"

        /** 2 added [BackupPodcast.feeds] and [BackupEpisode.feedUrl]; version 1 files still import. */
        const val VERSION = 2
    }
}

@Serializable
data class BackupPodcast(
    val feedUrl: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val keepEpisodes: Boolean = false,
    /** Every feed combined into this podcast, including the original ([feedUrl]). Empty in version 1 files. */
    val feeds: List<BackupFeed> = emptyList(),
    val episodes: List<BackupEpisode> = emptyList(),
)

@Serializable
data class BackupFeed(
    val url: String,
    val title: String? = null,
    val author: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val preferred: Boolean = false,
) {
    companion object {
        fun from(f: Feed) = BackupFeed(f.url, f.title, f.author, f.description, f.imageUrl, f.preferred)
    }
}

@Serializable
data class BackupEpisode(
    val guid: String,
    val title: String,
    val description: String? = null,
    val pubDate: Long = 0,
    val audioUrl: String,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val played: Boolean = false,
    val lastPlayedAt: Long? = null,
    /** The feed this episode came from; null means the podcast's original feed. */
    val feedUrl: String? = null,
) {
    val hasListeningState: Boolean get() = played || positionMs > 0

    fun toEpisode(podcastId: Long, feedId: Long) = Episode(
        podcastId = podcastId,
        feedId = feedId,
        guid = guid,
        title = title,
        description = description,
        pubDate = pubDate,
        audioUrl = audioUrl,
        durationMs = durationMs,
        positionMs = positionMs,
        played = played,
        lastPlayedAt = lastPlayedAt,
    )

    companion object {
        fun from(e: Episode, feedUrl: String? = null) = BackupEpisode(
            guid = e.guid,
            title = e.title,
            description = e.description,
            pubDate = e.pubDate,
            audioUrl = e.audioUrl,
            durationMs = e.durationMs,
            positionMs = e.positionMs,
            played = e.played,
            lastPlayedAt = e.lastPlayedAt,
            feedUrl = feedUrl,
        )
    }
}

@Serializable
data class BackupQueueItem(val feedUrl: String, val guid: String)

object BackupJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(backup: BackupFile): String = json.encodeToString(BackupFile.serializer(), backup)

    fun decode(text: String): BackupFile {
        val backup = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: Exception) {
            throw IOException("That file isn't a valid backup", e)
        }
        if (backup.format != BackupFile.FORMAT) throw IOException("That file isn't a whalepod backup")
        if (backup.version > BackupFile.VERSION) throw IOException("This backup was made by a newer version of the app")
        return backup
    }
}

/**
 * Applies an episode's listening state from a backup onto the stored episode. The backup wins when it was
 * listened to more recently, or when the stored copy has never been touched; otherwise local state is kept.
 */
fun restoreListeningState(existing: Episode, backup: BackupEpisode): Episode =
    mergeListeningState(existing, backup.positionMs, backup.played, backup.lastPlayedAt)
