package com.mazedev.island.media

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import com.mazedev.island.util.Apps
import com.mazedev.island.util.sendCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class MediaState(
    val packageName: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val isPlaying: Boolean,
    val accent: Int,
    val canPlayFromUri: Boolean,
)

data class NotifEvent(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val icon: Bitmap?,
    val contentIntent: PendingIntent?,
)

/**
 * Shared, in-process source of truth. The notification listener writes into it;
 * the island, the widget and the settings screen read from it.
 */
object MediaRepository {
    const val SPOTIFY = "com.spotify.music"

    private val _media = MutableStateFlow<MediaState?>(null)
    /** Whatever is playing right now (any app). Used by the island. */
    val media: StateFlow<MediaState?> = _media.asStateFlow()

    private val _spotify = MutableStateFlow<MediaState?>(null)
    /** Spotify's session only. Used by the widget. */
    val spotify: StateFlow<MediaState?> = _spotify.asStateFlow()

    private val _notifications = MutableSharedFlow<NotifEvent>(extraBufferCapacity = 8)
    val notifications: SharedFlow<NotifEvent> = _notifications.asSharedFlow()

    @Volatile private var activeController: MediaController? = null
    @Volatile private var spotifyController: MediaController? = null

    internal fun publish(
        active: MediaController?,
        activeState: MediaState?,
        spotify: MediaController?,
        spotifyState: MediaState?,
    ) {
        activeController = active
        spotifyController = spotify
        _media.value = activeState
        _spotify.value = spotifyState
    }

    internal fun emitNotification(event: NotifEvent) {
        _notifications.tryEmit(event)
    }

    /** Uses the cached controller, or asks the system directly if our process was just restarted. */
    private fun controllerFor(context: Context, spotifyOnly: Boolean): MediaController? {
        val cached = if (spotifyOnly) spotifyController else activeController
        if (cached != null) return cached
        return try {
            val msm = context.getSystemService(MediaSessionManager::class.java)
            val list = msm.getActiveSessions(ComponentName(context, MediaListenerService::class.java))
            if (spotifyOnly) list.firstOrNull { it.packageName == SPOTIFY } else list.firstOrNull()
        } catch (_: SecurityException) {
            null
        }
    }

    fun playPause(context: Context, spotifyOnly: Boolean = false) {
        val c = controllerFor(context, spotifyOnly)
        if (c == null) {
            if (!spotifyOnly) sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            return
        }
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
    }

    fun next(context: Context, spotifyOnly: Boolean = false) {
        controllerFor(context, spotifyOnly)?.transportControls?.skipToNext()
    }

    fun previous(context: Context, spotifyOnly: Boolean = false) {
        controllerFor(context, spotifyOnly)?.transportControls?.skipToPrevious()
    }

    /**
     * Best effort: asks Spotify's media session to start a playlist directly.
     * Returns false if Spotify isn't running or doesn't accept it.
     */
    fun playSpotifyUri(context: Context, uri: String): Boolean {
        val c = controllerFor(context, spotifyOnly = true) ?: return false
        val actions = c.playbackState?.actions ?: 0L
        if ((actions and PlaybackState.ACTION_PLAY_FROM_URI) == 0L) return false
        c.transportControls.playFromUri(Uri.parse(uri), Bundle())
        return true
    }

    /** Opens the app that's playing (its "now playing" screen if it provides one). */
    fun openPlayer(context: Context) {
        val c = activeController ?: return
        val activity = c.sessionActivity
        if (activity != null) activity.sendCompat() else Apps.launch(context, c.packageName)
    }

    private fun sendMediaKey(context: Context, code: Int) {
        val am = context.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}
