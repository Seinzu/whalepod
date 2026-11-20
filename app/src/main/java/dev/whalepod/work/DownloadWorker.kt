package dev.whalepod.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dev.whalepod.PodcastApp
import dev.whalepod.data.db.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Downloads one episode's audio file. Supports resuming a partial download via HTTP Range. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val container = (context.applicationContext as PodcastApp).container
    private val dao = container.db.episodeDao()

    override suspend fun doWork(): Result {
        val episodeId = inputData.getLong(KEY_EPISODE_ID, -1)
        val episode = dao.get(episodeId) ?: return Result.failure()
        val target = container.downloader.fileFor(episode)
        if (episode.downloadState == DownloadState.DOWNLOADED && target.exists()) return Result.success()

        runCatching { setForeground(foregroundInfo(episode.title, 0)) } // best effort; may be refused in background
        dao.setDownloadState(episodeId, DownloadState.DOWNLOADING, 0)

        return try {
            download(episode.audioUrl, target, episode.title, episodeId)
            dao.setDownloaded(episodeId, target.absolutePath)
            Result.success()
        } catch (e: CancellationException) {
            // Stopped by the system (e.g. Wi-Fi lost) — WorkManager will resume it later. If the user
            // cancelled, the state has already been cleared to NONE and is left alone.
            withContext(NonCancellable) {
                if (dao.get(episodeId)?.downloadState == DownloadState.DOWNLOADING) {
                    dao.setDownloadState(episodeId, DownloadState.QUEUED)
                }
            }
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS - 1) {
                dao.setDownloadState(episodeId, DownloadState.QUEUED)
                Result.retry()
            } else {
                dao.setDownloadState(episodeId, DownloadState.FAILED)
                Result.failure()
            }
        }
    }

    private suspend fun download(url: String, target: File, title: String, episodeId: Long) =
        withContext(Dispatchers.IO) {
            val temp = tempFileFor(target)
            val already = if (temp.exists()) temp.length() else 0L
            val request = Request.Builder().url(url).apply {
                if (already > 0) header("Range", "bytes=$already-")
            }.build()

            container.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty body")
                val append = response.code == 206 && already > 0
                val offset = if (append) already else 0L
                val total = body.contentLength().takeIf { it > 0 }?.plus(offset) ?: -1L

                var written = offset
                var lastReport = 0L
                body.byteStream().use { input ->
                    FileOutputStream(temp, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (isStopped) throw CancellationException("Stopped")
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            written += n
                            val now = System.currentTimeMillis()
                            if (total > 0 && now - lastReport > 1000) {
                                lastReport = now
                                val pct = (written * 100 / total).toInt().coerceIn(0, 99)
                                dao.setDownloadState(episodeId, DownloadState.DOWNLOADING, pct)
                                runCatching { setForeground(foregroundInfo(title, pct)) }
                            }
                        }
                    }
                }
                if (total > 0 && written < total) throw IOException("Connection closed early")
            }
            target.delete()
            if (!temp.renameTo(target)) throw IOException("Couldn't save file")
        }

    private fun foregroundInfo(title: String, progress: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, PodcastApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading")
            .setContentText(title)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val notificationId = NOTIFICATION_BASE + (inputData.getLong(KEY_EPISODE_ID, 0) % 10_000).toInt()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val KEY_EPISODE_ID = "episodeId"
        const val TAG = "episode-download"
        private const val MAX_ATTEMPTS = 4
        private const val NOTIFICATION_BASE = 20_000

        fun tempFileFor(target: File) = File(target.parentFile, target.name + ".part")
    }
}
