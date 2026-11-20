package dev.simplepod.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.simplepod.data.QueueRepository
import dev.simplepod.data.db.AppDatabase
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NowPlaying(
    val episodeId: Long? = null,
    val podcastId: Long? = null,
    val title: String = "",
    val podcastTitle: String = "",
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/**
 * The UI's handle on playback: a [MediaController] connected to [PlaybackService], plus a [StateFlow]
 * describing what's playing. Connect while the activity is started.
 */
class PlayerController(
    private val context: Context,
    private val db: AppDatabase,
    private val queue: QueueRepository,
) {
    private val scope = MainScope()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ticker: Job? = null
    private var pending: (MediaController.() -> Unit)? = null

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            refreshFromController()
            pending?.invoke(c)
            pending = null
        }, ContextCompat.getMainExecutor(context))
    }

    fun disconnect() {
        ticker?.cancel()
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    /** Plays an episode from its remembered position (the service looks the episode up). */
    fun play(episodeId: Long) = withController {
        setMediaItem(MediaItem.Builder().setMediaId(episodeId.toString()).build())
        prepare()
        play()
    }

    fun togglePlayPause() = withController {
        if (isPlaying) pause() else {
            if (playbackState == Player.STATE_IDLE) prepare()
            play()
        }
    }

    fun seekBack() = withController { seekBack() }
    fun seekForward() = withController { seekForward() }

    fun seekTo(positionMs: Long) = withController {
        seekTo(positionMs)
        _state.update { it.copy(positionMs = positionMs) }
    }

    /** Moves on to the first item in the queue. */
    fun next() {
        scope.launch { queue.first()?.let { play(it) } }
    }

    fun stop() = withController {
        stop()
        clearMediaItems()
    }

    private fun withController(block: MediaController.() -> Unit) {
        val c = controller
        if (c != null) c.block() else {
            pending = block
            connect()
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refreshFromController()
    }

    private fun refreshFromController() {
        val c = controller ?: return
        val episodeId = c.currentMediaItem?.mediaId?.toLongOrNull()
        _state.update {
            it.copy(
                episodeId = episodeId,
                isPlaying = c.isPlaying,
                isBuffering = c.playbackState == Player.STATE_BUFFERING,
                positionMs = c.currentPosition.coerceAtLeast(0),
                durationMs = c.duration.takeIf { d -> d != C.TIME_UNSET && d > 0 } ?: it.durationMs,
            )
        }
        if (episodeId == null) {
            loadedDetailsFor = null
            _state.value = NowPlaying()
        } else if (episodeId != loadedDetailsFor) {
            loadDetails(episodeId)
        }
        ticker?.cancel()
        if (c.isPlaying) {
            ticker = scope.launch {
                while (isActive) {
                    delay(500)
                    _state.update { it.copy(positionMs = c.currentPosition.coerceAtLeast(0)) }
                }
            }
        }
    }

    private var loadedDetailsFor: Long? = null

    private fun loadDetails(episodeId: Long) {
        loadedDetailsFor = episodeId
        _state.update { it.copy(durationMs = 0) }
        scope.launch {
            val row = db.episodeDao().getWithPodcast(episodeId) ?: return@launch
            _state.update {
                if (it.episodeId != episodeId) it else it.copy(
                    podcastId = row.episode.podcastId,
                    title = row.episode.title,
                    podcastTitle = row.podcastTitle,
                    artworkUrl = row.podcastImageUrl,
                    durationMs = if (it.durationMs > 0) it.durationMs else row.episode.durationMs,
                )
            }
        }
    }
}
