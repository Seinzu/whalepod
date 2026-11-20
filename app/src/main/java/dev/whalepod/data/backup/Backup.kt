package dev.whalepod.data.backup

import dev.whalepod.data.db.Episode
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
    val version: Int = 1,
    val exportedAt: Long = 0,
    val podcasts: List<BackupPodcast> = emptyList(),
    val queue: List<BackupQueueItem> = emptyList(),
) {
    companion object {
        const val FORMAT = "whalepod-backup"
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
    val episodes: List<BackupEpisode> = emptyList(),
)

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
) {
    val hasListeningState: Boolean get() = played || positionMs > 0

    fun toEpisode(podcastId: Long) = Episode(
        podcastId = podcastId,
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
        fun from(e: Episode) = BackupEpisode(
            guid = e.guid,
            title = e.title,
            description = e.description,
            pubDate = e.pubDate,
            audioUrl = e.audioUrl,
            durationMs = e.durationMs,
            positionMs = e.positionMs,
            played = e.played,
            lastPlayedAt = e.lastPlayedAt,
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
        if (backup.version > 1) throw IOException("This backup was made by a newer version of the app")
        return backup
    }
}

/**
 * Applies an episode's listening state from a backup onto the stored episode. The backup wins when it was
 * listened to more recently, or when the stored copy has never been touched; otherwise local state is kept.
 */
fun restoreListeningState(existing: Episode, backup: BackupEpisode): Episode {
    val backupIsNewer = backup.lastPlayedAt != null &&
        (existing.lastPlayedAt == null || backup.lastPlayedAt > existing.lastPlayedAt)
    val existingUntouched = existing.lastPlayedAt == null && !existing.played && existing.positionMs == 0L
    return if (backupIsNewer || (existingUntouched && backup.hasListeningState)) {
        existing.copy(positionMs = backup.positionMs, played = backup.played, lastPlayedAt = backup.lastPlayedAt)
    } else {
        existing
    }
}
