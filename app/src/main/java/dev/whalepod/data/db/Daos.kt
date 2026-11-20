package dev.whalepod.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {
    @Query("SELECT * FROM podcasts ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<Podcast>>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    fun observe(id: Long): Flow<Podcast?>

    @Query("SELECT * FROM podcasts ORDER BY title COLLATE NOCASE")
    suspend fun getAll(): List<Podcast>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun get(id: Long): Podcast?

    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl")
    suspend fun getByFeedUrl(feedUrl: String): Podcast?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(podcast: Podcast): Long

    @Update
    suspend fun update(podcast: Podcast)

    @Query("UPDATE podcasts SET keepEpisodes = :keep WHERE id = :id")
    suspend fun setKeepEpisodes(id: Long, keep: Boolean)

    @Query("DELETE FROM podcasts WHERE id = :id")
    suspend fun delete(id: Long)
}

private const val WITH_PODCAST =
    "SELECT e.*, p.title AS podcastTitle, p.imageUrl AS podcastImageUrl " +
        "FROM episodes e JOIN podcasts p ON p.id = e.podcastId"

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDate DESC, id DESC")
    fun observeForPodcast(podcastId: Long): Flow<List<Episode>>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId")
    suspend fun getForPodcast(podcastId: Long): List<Episode>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun get(id: Long): Episode?

    @Query("$WITH_PODCAST WHERE e.id = :id")
    suspend fun getWithPodcast(id: Long): EpisodeWithPodcast?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(episodes: List<Episode>): List<Long>

    @Update
    suspend fun updateAll(episodes: List<Episode>)

    @Query("UPDATE episodes SET positionMs = :positionMs, lastPlayedAt = :now WHERE id = :id AND played = 0")
    suspend fun updatePosition(id: Long, positionMs: Long, now: Long)

    @Query("UPDATE episodes SET durationMs = :durationMs WHERE id = :id")
    suspend fun updateDuration(id: Long, durationMs: Long)

    @Query("UPDATE episodes SET played = :played, positionMs = :positionMs WHERE id = :id")
    suspend fun setPlayed(id: Long, played: Boolean, positionMs: Long)

    @Query("UPDATE episodes SET downloadState = :state, downloadProgress = :progress WHERE id = :id")
    suspend fun setDownloadState(id: Long, state: DownloadState, progress: Int = 0)

    @Query(
        "UPDATE episodes SET downloadState = 'DOWNLOADED', downloadProgress = 100, " +
            "localFilePath = :path WHERE id = :id",
    )
    suspend fun setDownloaded(id: Long, path: String)

    @Query(
        "UPDATE episodes SET downloadState = 'NONE', downloadProgress = 0, " +
            "localFilePath = NULL WHERE id = :id",
    )
    suspend fun clearDownload(id: Long)
}

@Dao
interface QueueDao {
    @Query(
        "SELECT e.*, p.title AS podcastTitle, p.imageUrl AS podcastImageUrl FROM queue_items q " +
            "JOIN episodes e ON e.id = q.episodeId JOIN podcasts p ON p.id = e.podcastId " +
            "ORDER BY q.sortOrder",
    )
    fun observe(): Flow<List<EpisodeWithPodcast>>

    @Query("SELECT episodeId FROM queue_items ORDER BY sortOrder")
    suspend fun episodeIds(): List<Long>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM queue_items")
    suspend fun maxSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: QueueItem): Long

    @Query("DELETE FROM queue_items WHERE episodeId = :episodeId")
    suspend fun remove(episodeId: Long)

    @Query("UPDATE queue_items SET sortOrder = :sortOrder WHERE episodeId = :episodeId")
    suspend fun setSortOrder(episodeId: Long, sortOrder: Int)
}
