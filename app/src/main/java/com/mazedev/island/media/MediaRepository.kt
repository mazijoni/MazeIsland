package com.mazedev.island.media

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
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
    val appName: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    /** Small pre-blurred copy of the cover, for backgrounds. */
    val blurred: Bitmap?,
    val isPlaying: Boolean,
    val accent: Int,
    val canPlayFromUri: Boolean,
    /** When it was last playing (now, if it's playing). 0 = never seen playing. */
    val lastActiveAt: Long,
) {
    /** Playing, or paused less than a minute ago. */
    fun isRecent(now: Long = System.currentTimeMillis()): Boolean =
        isPlaying || (lastActiveAt > 0 && now - lastActiveAt < RECENT_MS)

    companion object {
        const val RECENT_MS = 60_000L
    }
}

data class NotifEvent(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val icon: Bitmap?,
    val contentIntent: PendingIntent?,
    /** Non-null if the notification has an inline "Reply" action. */
    val replyAction: Notification.Action?,
    val postedAt: Long = System.currentTimeMillis(),
)

/**
 * Shared, in-process source of truth. The notification listener writes into it;
 * the island, the widget and the settings screen read from it.
 */
object MediaRepository {
    const val SPOTIFY = "com.spotify.music"

    private val _sessions = MutableStateFlow<List<MediaState>>(emptyList())
    /** Every media app with something loaded. Spotify first, then most recently active. */
    val sessions: StateFlow<List<MediaState>> = _sessions.asStateFlow()

    private val _media = MutableStateFlow<MediaState?>(null)
    /** The one to show in the collapsed island: what's playing, Spotify preferred. */
    val media: StateFlow<MediaState?> = _media.asStateFlow()

    private val _spotify = MutableStateFlow<MediaState?>(null)
    /** Spotify's session only. Used by the widget. */
    val spotify: StateFlow<MediaState?> = _spotify.asStateFlow()

    private val _notifications = MutableSharedFlow<NotifEvent>(extraBufferCapacity = 8)
    val notifications: SharedFlow<NotifEvent> = _notifications.asSharedFlow()

    @Volatile private var controllers: Map<String, MediaController> = emptyMap()

    internal fun publish(states: List<MediaState>, byPackage: Map<String, MediaController>) {
        controllers = byPackage
        _sessions.value = states
        _media.value = states.firstOrNull { it.isPlaying && it.packageName == SPOTIFY }
            ?: states.firstOrNull { it.isPlaying }
            ?: states.firstOrNull()
        _spotify.value = states.firstOrNull { it.packageName == SPOTIFY }
    }

    internal fun emitNotification(event: NotifEvent) {
        _notifications.tryEmit(event)
    }

    /** Uses the cached controller, or asks the system directly if our process was just restarted. */
    private fun controllerFor(context: Context, pkg: String?): MediaController? {
        val cached = if (pkg != null) controllers[pkg] else controllers[_media.value?.packageName]
        if (cached != null) return cached
        return try {
            val msm = context.getSystemService(MediaSessionManager::class.java)
            val list = msm.getActiveSessions(ComponentName(context, MediaListenerService::class.java))
            if (pkg != null) list.firstOrNull { it.packageName == pkg } else list.firstOrNull()
        } catch (_: SecurityException) {
            null
        }
    }

    /** [pkg] null = whatever is currently the main session. */
    fun playPause(context: Context, pkg: String? = null) {
        val c = controllerFor(context, pkg)
        if (c == null) {
            if (pkg == null) sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            return
        }
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
    }

    fun next(context: Context, pkg: String? = null) {
        controllerFor(context, pkg)?.transportControls?.skipToNext()
    }

    fun previous(context: Context, pkg: String? = null) {
        controllerFor(context, pkg)?.transportControls?.skipToPrevious()
    }

    /** Best effort: ask Spotify to start a playlist directly. False if it can't. */
    fun playSpotifyUri(context: Context, uri: String): Boolean {
        val c = controllerFor(context, SPOTIFY) ?: return false
        val actions = c.playbackState?.actions ?: 0L
        if ((actions and PlaybackState.ACTION_PLAY_FROM_URI) == 0L) return false
        c.transportControls.playFromUri(Uri.parse(uri), Bundle())
        return true
    }

    /** Opens the player app (its "now playing" screen if it provides one). */
    fun openPlayer(context: Context, pkg: String) {
        val activity = controllers[pkg]?.sessionActivity
        if (activity != null) activity.sendCompat() else Apps.launch(context, pkg)
    }

    /** Sends [text] through a notification's inline reply action. */
    fun sendReply(context: Context, action: Notification.Action, text: String): Boolean {
        val inputs = action.remoteInputs ?: return false
        if (inputs.isEmpty()) return false
        return try {
            val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            val results = Bundle()
            inputs.filter { it.allowFreeFormInput }.forEach { results.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            if (Build.VERSION.SDK_INT >= 28) {
                RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            }
            action.actionIntent.send(context, 0, intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun sendMediaKey(context: Context, code: Int) {
        val am = context.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}
