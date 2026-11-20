package dev.whalepod.data

import androidx.room.withTransaction
import dev.whalepod.data.db.AppDatabase
import dev.whalepod.data.db.EpisodeWithPodcast
import dev.whalepod.data.db.QueueItem
import kotlinx.coroutines.flow.Flow

/** The play queue, stored in Room so it survives restarts. */
class QueueRepository(private val db: AppDatabase) {
    private val dao = db.queueDao()

    fun observe(): Flow<List<EpisodeWithPodcast>> = dao.observe()

    suspend fun add(episodeId: Long) {
        db.withTransaction {
            dao.insert(QueueItem(episodeId = episodeId, sortOrder = dao.maxSortOrder() + 1))
        }
    }

    suspend fun remove(episodeId: Long) = dao.remove(episodeId)

    suspend fun first(): Long? = dao.episodeIds().firstOrNull()

    suspend fun move(episodeId: Long, delta: Int) {
        db.withTransaction {
            val reordered = moveItem(dao.episodeIds(), episodeId, delta)
            reordered.forEachIndexed { index, id -> dao.setSortOrder(id, index) }
        }
    }
}

/** Returns [ids] with [id] moved by [delta] places, clamped to the list bounds. */
fun moveItem(ids: List<Long>, id: Long, delta: Int): List<Long> {
    val from = ids.indexOf(id)
    if (from < 0) return ids
    val to = (from + delta).coerceIn(0, ids.lastIndex)
    if (to == from) return ids
    return ids.toMutableList().apply { add(to, removeAt(from)) }
}
