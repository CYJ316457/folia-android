package top.izuna.foliamajor.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Playback service that keeps audio running with the screen off.
 *
 * This is the single capability that justifies a native client: the WebView-based packaging routes
 * cannot hold a media foreground service, so playback stops when the screen locks. Everything the
 * system needs for background playback — the media notification, the lock-screen controls, the
 * audio focus and becoming-noisy handling — comes from MediaSessionService, which is why the player
 * and session are created here rather than in the UI layer.
 */
class FoliaPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Pausing instead of ducking: the lyric renderer is the point of this app, and a ducked
            // volume makes the word timings unreadable while a notification is talking.
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openAppIntent())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * Called when the user swipes the app away from recents.
     *
     * Playback is stopped rather than continued: a lyric player with no visible UI is indistinguishable
     * from a stuck background process, and the session would keep the foreground notification alive
     * with no way back to it.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        mediaSession?.player?.let { player ->
            if (!player.playWhenReady || player.mediaItemCount == 0) {
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, top.izuna.foliamajor.MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** Exposed so the UI can observe the same player instance the service owns. */
val Player.isActuallyPlaying: Boolean
    get() = isPlaying && playbackState == Player.STATE_READY
