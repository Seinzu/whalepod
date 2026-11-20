package dev.whalepod.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import dev.whalepod.MainActivity
import dev.whalepod.PodcastApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Owns the ExoPlayer and the media session. The player only ever holds the current episode; the queue
 * lives in Room and the service advances through it when an episode ends.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private val container by lazy { (application as PodcastApp).container }
    private val scope: CoroutineScope = MainScope()
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private var positionSaver: Job? = null

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
        player.addListener(playerListener)

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(
                ImmutableList.of(
                    CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
                        .setDisplayName("Back 10 seconds")
                        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                        .setSlots(CommandButton.SLOT_BACK)
                        .build(),
                    CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
                        .setDisplayName("Forward 30 seconds")
                        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                        .setSlots(CommandButton.SLOT_FORWARD)
                        .build(),
                ),
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveCurrentPositionBlocking()
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        saveCurrentPositionBlocking()
        session?.release()
        session = null
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    // ---- Session callback: resolve episode ids sent by the UI into playable items ----------------

    private val sessionCallback = object : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.mapNotNull { item -> item.mediaId.toLongOrNull()?.let { resolve(it)?.first } }.toMutableList()
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Capture the outgoing episode's state now, before the player switches items.
            val outgoing = snapshot()
            return scope.future {
                outgoing?.let { finishOrSave(it) }
                val id = mediaItems.firstOrNull()?.mediaId?.toLongOrNull()
                val resolved = id?.let { prepareToPlay(it) }
                if (resolved == null) {
                    MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
                } else {
                    val start = if (startPositionMs != C.TIME_UNSET) startPositionMs else resolved.second
                    MediaSession.MediaItemsWithStartPosition(listOf(resolved.first), 0, start)
                }
            }
        }
    }

    // ---- Player events ----------------------------------------------------------------------

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            positionSaver?.cancel()
            if (isPlaying) {
                positionSaver = scope.launch {
                    while (isActive) {
                        delay(SAVE_INTERVAL_MS)
                        snapshot()?.let { save(it) }
                    }
                }
            } else if (player.playbackState != Player.STATE_ENDED) {
                snapshot()?.let { scope.launch { save(it) } }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) snapshot()?.let { scope.launch { save(it) } }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    val id = currentEpisodeId() ?: return
                    val duration = player.duration
                    if (duration != C.TIME_UNSET && duration > 0) {
                        scope.launch { container.db.episodeDao().updateDuration(id, duration) }
                    }
                }
                Player.STATE_ENDED -> {
                    val id = currentEpisodeId() ?: return
                    scope.launch { onEpisodeEnded(id) }
                }
            }
        }
    }

    private suspend fun onEpisodeEnded(episodeId: Long) {
        container.repository.setPlayed(episodeId, true)
        val next = container.queue.first()
        val resolved = next?.let { prepareToPlay(it) }
        if (resolved == null) {
            player.stop()
            player.clearMediaItems()
            return
        }
        player.setMediaItem(resolved.first, resolved.second)
        player.prepare()
        player.play()
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private data class Snapshot(val episodeId: Long, val positionMs: Long, val durationMs: Long)

    private fun currentEpisodeId(): Long? = player.currentMediaItem?.mediaId?.toLongOrNull()

    private fun snapshot(): Snapshot? {
        val id = currentEpisodeId() ?: return null
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        return Snapshot(id, player.currentPosition.coerceAtLeast(0), duration)
    }

    private suspend fun save(s: Snapshot) {
        container.db.episodeDao().updatePosition(s.episodeId, s.positionMs, System.currentTimeMillis())
    }

    /** When switching away from an episode: count it as played if it was within 30 s of the end. */
    private suspend fun finishOrSave(s: Snapshot) {
        if (s.durationMs > 0 && s.durationMs - s.positionMs <= NEAR_END_MS) {
            container.repository.setPlayed(s.episodeId, true)
        } else {
            save(s)
        }
    }

    private fun saveCurrentPositionBlocking() {
        val s = snapshot() ?: return
        runBlocking { withContext(NonCancellable) { save(s) } }
    }

    /** Removes the episode from the queue, clears its played flag and returns (item, resume position). */
    private suspend fun prepareToPlay(episodeId: Long): Pair<MediaItem, Long>? {
        val resolved = resolve(episodeId) ?: return null
        container.queue.remove(episodeId)
        container.db.episodeDao().get(episodeId)?.let {
            if (it.played) container.db.episodeDao().setPlayed(episodeId, false, it.positionMs)
        }
        return resolved
    }

    /** Builds a playable item for an episode — local file if downloaded, otherwise the stream URL. */
    private suspend fun resolve(episodeId: Long): Pair<MediaItem, Long>? {
        val row = container.db.episodeDao().getWithPodcast(episodeId) ?: return null
        val episode = row.episode
        val file = container.downloader.existingFile(episode)
        val uri = file?.let { Uri.fromFile(it) } ?: Uri.parse(episode.audioUrl)
        val item = MediaItem.Builder()
            .setMediaId(episode.id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(row.podcastTitle)
                    .setAlbumTitle(row.podcastTitle)
                    .setArtworkUri(row.podcastImageUrl?.let(Uri::parse))
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .build(),
            )
            .build()
        val resumeAt = if (episode.durationMs > 0 && episode.durationMs - episode.positionMs <= NEAR_END_MS) {
            0L
        } else {
            episode.positionMs
        }
        return item to resumeAt
    }

    companion object {
        const val SEEK_BACK_MS = 10_000L
        const val SEEK_FORWARD_MS = 30_000L
        const val NEAR_END_MS = 30_000L
        private const val SAVE_INTERVAL_MS = 5_000L
    }
}
