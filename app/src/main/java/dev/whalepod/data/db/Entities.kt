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

/**
 * One RSS feed. A podcast normally has a single feed, but several can be combined into one podcast
 * (e.g. a free feed and a paid, ad-free feed). The podcast's name and artwork come from its original
 * feed — the one whose URL is [Podcast.feedUrl].
 */
@Entity(
    tableName = "feeds",
    foreignKeys = [
        ForeignKey(
            entity = Podcast::class,
            parentColumns = ["id"],
            childColumns = ["podcastId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["url"], unique = true), Index(value = ["podcastId"])],
)
data class Feed(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val podcastId: Long,
    val url: String,
    val title: String? = null,
    val author: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    /** When the same episode is in several feeds, the copy from the preferred feed is the one shown. */
    val preferred: Boolean = false,
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
    indices = [Index(value = ["feedId", "guid"], unique = true), Index(value = ["podcastId"])],
)
data class Episode(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val podcastId: Long,
    /** The feed this episode came from. */
    @ColumnInfo(defaultValue = "0") val feedId: Long,
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
    /**
     * Set when this episode is a copy of another episode of the same podcast from a more preferred feed
     * (e.g. the free copy of an ad-free episode). Duplicates are hidden from the episode list.
     */
    val duplicateOfId: Long? = null,
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
