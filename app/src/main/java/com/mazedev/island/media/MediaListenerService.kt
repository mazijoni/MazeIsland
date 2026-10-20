package com.mazedev.island.media

import android.app.Notification
import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.appwidget.updateAll
import com.mazedev.island.Prefs
import com.mazedev.island.util.Apps
import com.mazedev.island.util.BitmapUtils
import com.mazedev.island.widget.PlaylistWidget
import com.mazedev.island.widget.WidgetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Granting "Notification access" to this service is what lets us:
 *  - see every active media session (Spotify, YouTube Music, …) with cover art + controls
 *  - see incoming notifications (texts) to pop them up in the island
 */
class MediaListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionManager: MediaSessionManager? = null
    private val callbacks = LinkedHashMap<MediaController, MediaController.Callback>()
    private val artCache = HashMap<String, Triple<Bitmap, Bitmap, Int>>()
    private val lastShown = HashMap<String, String>()
    private var widgetJob: Job? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        onSessionsChanged(controllers.orEmpty())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Prefs.init(this)
        val msm = getSystemService(MediaSessionManager::class.java)
        sessionManager = msm
        val component = ComponentName(this, MediaListenerService::class.java)
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component)
            onSessionsChanged(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            Log.w(TAG, "No media session access yet", e)
        }
    }

    override fun onListenerDisconnected() {
        cleanup()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        cleanup()
        scope.cancel()
        super.onDestroy()
    }

    private fun cleanup() {
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        callbacks.forEach { (c, cb) -> c.unregisterCallback(cb) }
        callbacks.clear()
    }

    // ---------------------------------------------------------------- media

    private fun onSessionsChanged(controllers: List<MediaController>) {
        callbacks.forEach { (c, cb) -> c.unregisterCallback(cb) }
        callbacks.clear()
        val handler = Handler(Looper.getMainLooper())
        controllers.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
                override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
                override fun onSessionDestroyed() = refresh()
            }
            c.registerCallback(cb, handler)
            callbacks[c] = cb
        }
        refresh()
    }

    private fun refresh() {
        val controllers = callbacks.keys.toList()
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val active = playing ?: controllers.firstOrNull { it.metadata != null }
        val spotify = controllers.firstOrNull { it.packageName == MediaRepository.SPOTIFY }
        MediaRepository.publish(
            active = active,
            activeState = active?.let { toState(it) },
            spotify = spotify,
            spotifyState = spotify?.let { toState(it) },
        )
        scheduleWidgetUpdate()
    }

    private fun toState(c: MediaController): MediaState? {
        val md = c.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val raw = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val art = artFor(c.packageName, raw)
        val state = c.playbackState
        val actions = state?.actions ?: 0L
        return MediaState(
            packageName = c.packageName,
            title = title,
            artist = artist,
            art = art?.first,
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            accent = art?.second ?: 0xFF1DB954.toInt(),
            canPlayFromUri = (actions and PlaybackState.ACTION_PLAY_FROM_URI) != 0L,
        )
    }

    /** Scales the cover once per track (not on every playback tick). */
    private fun artFor(pkg: String, raw: Bitmap?): Pair<Bitmap, Int>? {
        if (raw == null) return null
        artCache[pkg]?.let { if (it.first === raw) return it.second to it.third }
        return runCatching {
            val scaled = BitmapUtils.scaleToMax(raw, 320)
            val accent = BitmapUtils.accent(scaled)
            artCache[pkg] = Triple(raw, scaled, accent)
            scaled to accent
        }.getOrNull()
    }

    private fun scheduleWidgetUpdate() {
        val state = MediaRepository.spotify.value
        widgetJob?.cancel()
        widgetJob = scope.launch {
            delay(250) // coalesce bursts of callbacks
            withContext(Dispatchers.IO) { WidgetStore.saveNowPlaying(applicationContext, state) }
            runCatching { PlaylistWidget().updateAll(applicationContext) }
        }
    }

    // -------------------------------------------------------- notifications

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        val extras = n.extras ?: return

        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return // music player notification
        if (sbn.isOngoing) return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.category in IGNORED_CATEGORIES) return

        val isMessage = n.category == Notification.CATEGORY_MESSAGE ||
            n.category == Notification.CATEGORY_EMAIL ||
            n.category == Notification.CATEGORY_CALL ||
            extras.containsKey(Notification.EXTRA_MESSAGES)
        if (!isMessage && !Prefs.island.value.showAllNotifications) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()

        // Apps often re-post the same notification; only pop when the content changed.
        val fingerprint = "$title|$text"
        if (lastShown[sbn.key] == fingerprint) return
        if (lastShown.size > 200) lastShown.clear()
        lastShown[sbn.key] = fingerprint

        val icon = runCatching {
            n.getLargeIcon()?.loadDrawable(this)?.toBitmap(96, 96)
                ?: packageManager.getApplicationIcon(sbn.packageName).toBitmap(96, 96)
        }.getOrNull()

        MediaRepository.emitNotification(
            NotifEvent(
                key = sbn.key,
                packageName = sbn.packageName,
                appName = Apps.label(this, sbn.packageName),
                title = title,
                text = text,
                icon = icon,
                contentIntent = n.contentIntent,
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let { lastShown.remove(it.key) }
    }

    companion object {
        private const val TAG = "MediaListener"
        private val IGNORED_CATEGORIES = setOf(
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_STATUS,
        )
    }
}
