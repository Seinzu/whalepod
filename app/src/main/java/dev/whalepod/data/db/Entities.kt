package dev.whalepod.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "podcasts",
    indices = [Index(value = ["feedUrl"], unique = true)],
)
data class Podcast(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedUrl: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    /** When false (the default) an episode's file is deleted once it has been played. */
    val keepEpisodes: Boolean = false,
    val lastRefreshedAt: Long = 0,
)

enum class DownloadState { NONE, QUEUED, DOWNLOADING, DOWNLOADED, FAILED }

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = Podcast::class,
            parentColumns = ["id"],
            childColumns = ["podcastId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["podcastId", "guid"], unique = true)],
)
data class Episode(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val podcastId: Long,
    val guid: String,
    val title: String,
    val description: String? = null,
    /** Epoch millis; 0 when the feed gave no parseable date. */
    val pubDate: Long = 0,
    val audioUrl: String,
    /** 0 when unknown. */
    val durationMs: Long = 0,
    // Download state
    val localFilePath: String? = null,
    val downloadState: DownloadState = DownloadState.NONE,
    val downloadProgress: Int = 0,
    // Playback state — kept even after the file is deleted.
    val positionMs: Long = 0,
    val played: Boolean = false,
    val lastPlayedAt: Long? = null,
)

@Entity(
    tableName = "queue_items",
    foreignKeys = [
        ForeignKey(
            entity = Episode::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["episodeId"], unique = true)],
)
data class QueueItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val episodeId: Long,
    val sortOrder: Int,
)

/** An episode joined with the bits of its podcast the player and queue need. */
data class EpisodeWithPodcast(
    @Embedded val episode: Episode,
    @ColumnInfo(name = "podcastTitle") val podcastTitle: String,
    @ColumnInfo(name = "podcastImageUrl") val podcastImageUrl: String?,
)
