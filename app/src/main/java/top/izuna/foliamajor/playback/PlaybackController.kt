package top.izuna.foliamajor.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the connection to [FoliaPlaybackService] and exposes playback as observable state.
 *
 * The controller is connected asynchronously — MediaSessionService lives in the same process but the
 * connection still resolves on a future — so every method here tolerates a null controller and the
 * UI renders "not ready" rather than waiting. Connecting from the UI process (instead of driving the
 * player directly) is what makes the notification, the lock screen and the app agree on one state.
 */
class PlaybackController(private val context: Context) {

    /** Mirrors the fields the UI actually reads; Media3's own Player is not observable from Compose. */
    data class State(
        val isConnected: Boolean = false,
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val mediaId: String? = null,
        val error: String? = null,
    ) {
        val positionSec: Double get() = positionMs / 1000.0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _state.value = _state.value.copy(
                isBuffering = false,
                error = error.cause?.message ?: error.message,
            )
        }
    }

    fun connect() {
        if (controllerFuture != null) return

        val token = SessionToken(context, ComponentName(context, FoliaPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future

        future.addListener(
            {
                runCatching { future.get() }.onSuccess { connected ->
                    controller = connected
                    connected.addListener(listener)
                    _state.value = _state.value.copy(isConnected = true)
                    publish(connected)
                    startPositionTicker()
                }.onFailure { error ->
                    _state.value = _state.value.copy(error = error.message)
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    /**
     * Polls the position instead of using Media3's own progress events.
     *
     * The lyric renderer needs a value that updates smoothly enough for word-level animation, and the
     * player only emits position updates a few times per second. A 16ms poll matches a frame, and the
     * read is a cheap field access on an in-process controller.
     */
    private fun startPositionTicker() {
        scope.launch {
            while (isActive) {
                controller?.let { publish(it) }
                delay(16)
            }
        }
    }

    private fun publish(player: Player) {
        val duration = player.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0L
        _state.value = _state.value.copy(
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            mediaId = player.currentMediaItem?.mediaId,
        )
    }

    fun playSong(songId: Long, streamUrl: String, title: String, artist: String, coverUrl: String?) {
        playUri(
            mediaId = songId.toString(),
            uri = streamUrl,
            title = title,
            artist = artist,
            coverUri = coverUrl,
        )
    }

    /**
     * Plays any URI the player can open: an HTTP stream, a `content://` document, or a local file.
     *
     * A single entry point rather than one per source because Media3 resolves all three through the
     * same data source machinery; the only thing that differs is the scheme, and the item's media id
     * is what the UI uses to tell the sources apart.
     */
    fun playUri(
        mediaId: String,
        uri: String,
        title: String,
        artist: String,
        coverUri: String?,
    ) {
        val player = controller ?: return

        val item = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(coverUri?.toUriOrNull())
                    .build()
            )
            .build()

        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    fun togglePlayPause() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    /**
     * Applies a ReplayGain multiplier on top of the user's volume.
     *
     * The player only has one volume knob, so the user's setting is remembered here and re-multiplied
     * on every gain change; otherwise switching tracks would either stack gains or lose the user's
     * level entirely.
     */
    fun setUserVolume(volume: Float) {
        userVolume = volume.coerceIn(0f, 1f)
        applyVolume()
    }

    private var userVolume: Float = 1f
    private var linearGain: Double = 1.0

    fun setReplayGain(calculation: ReplayGainCalculation) {
        linearGain = calculation.linearGain
        applyVolume()
    }

    private fun applyVolume() {
        controller?.volume = applyReplayGainToVolume(userVolume.toDouble(), linearGain).toFloat()
    }

    /** Seeking by lyric line is the primary way users navigate a lyric player. */
    fun seekToSeconds(seconds: Double) {
        seekTo((seconds * 1000).toLong())
    }

    fun release() {
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    private fun String.toUriOrNull(): android.net.Uri? =
        if (isBlank()) null else android.net.Uri.parse(this)
}
