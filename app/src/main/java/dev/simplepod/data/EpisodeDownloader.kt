package dev.simplepod.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.simplepod.data.db.AppDatabase
import dev.simplepod.data.db.DownloadState
import dev.simplepod.data.db.Episode
import dev.simplepod.work.DownloadWorker
import java.io.File

/** Schedules episode downloads (via WorkManager) and manages the downloaded files. */
class EpisodeDownloader(private val context: Context, private val db: AppDatabase) {

    val directory: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "episodes").apply { mkdirs() }

    fun fileFor(episode: Episode): File {
        val ext = episode.audioUrl.substringBefore('?').substringAfterLast('/', "")
            .substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }
            ?: "mp3"
        return File(directory, "${episode.id}.$ext")
    }

    /**
     * Queues a download. Automatic downloads wait for an unmetered (Wi-Fi) connection; manual ones
     * use any connection. Re-queuing replaces any earlier request for the same episode.
     */
    suspend fun enqueue(episodeId: Long, wifiOnly: Boolean) {
        db.episodeDao().setDownloadState(episodeId, DownloadState.QUEUED)
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(workDataOf(DownloadWorker.KEY_EPISODE_ID to episodeId))
            .addTag(DownloadWorker.TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(episodeId), ExistingWorkPolicy.REPLACE, request)
    }

    /** Cancels any pending download and deletes the episode's file. The episode record is kept. */
    suspend fun delete(episode: Episode) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(episode.id))
        episode.localFilePath?.let { File(it).delete() }
        fileFor(episode).delete()
        DownloadWorker.tempFileFor(fileFor(episode)).delete()
        db.episodeDao().clearDownload(episode.id)
    }

    /** Returns the local file if it exists; repairs the record if the file has gone missing. */
    suspend fun existingFile(episode: Episode): File? {
        val path = episode.localFilePath ?: return null
        val file = File(path)
        if (file.exists()) return file
        db.episodeDao().clearDownload(episode.id)
        return null
    }

    private fun workName(episodeId: Long) = "download-$episodeId"
}
