package com.mazedev.island.media

import android.app.Notification
import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
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
import com.mazedev.island.widget.DoorbellStore
import com.mazedev.island.widget.DoorbellWidget
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
 *  - see incoming notifications (texts) to pop them up in the island, and reply to them
 */
class MediaListenerService : NotificationListenerService() {

    private class Art(val source: Bitmap, val scaled: Bitmap, val blurred: Bitmap, val accent: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionManager: MediaSessionManager? = null
    private val callbacks = LinkedHashMap<MediaController, MediaController.Callback>()
    private val artCache = HashMap<String, Art>()
    private val lastActive = HashMap<String, Long>()
    private val wasPlaying = HashMap<String, Boolean>()
    private val lastShown = HashMap<String, String>()
    private val appNames = HashMap<String, String>()
    private var widgetJob: Job? = null
    private var lastWidgetKey: String? = null

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
        val now = System.currentTimeMillis()
        val byPackage = LinkedHashMap<String, MediaController>()
        val states = ArrayList<MediaState>()
        for (c in callbacks.keys) {
            if (byPackage.containsKey(c.packageName)) continue // one tab per app
            val s = toState(c, now) ?: continue
            byPackage[c.packageName] = c
            states += s
        }
        // Spotify first, then whatever was active most recently.
        states.sortWith(
            compareByDescending<MediaState> { it.packageName == MediaRepository.SPOTIFY }
                .thenByDescending { it.isPlaying }
                .thenByDescending { it.lastActiveAt }
        )
        MediaRepository.publish(states, byPackage)
        scheduleWidgetUpdate()
    }

    private fun toState(c: MediaController, now: Long): MediaState? {
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
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val pkg = c.packageName

        // "Last active" = now while playing, and the moment it got paused once it stops.
        if (playing || wasPlaying[pkg] == true) lastActive[pkg] = now
        wasPlaying[pkg] = playing

        val actions = state?.actions ?: 0L
        return MediaState(
            packageName = pkg,
            appName = appNames.getOrPut(pkg) { Apps.label(this, pkg) },
            title = title,
            artist = artist,
            art = art?.scaled,
            blurred = art?.blurred,
            isPlaying = playing,
            accent = art?.accent ?: 0xFF1DB954.toInt(),
            canPlayFromUri = (actions and PlaybackState.ACTION_PLAY_FROM_URI) != 0L,
            lastActiveAt = lastActive[pkg] ?: 0L,
        )
    }

    /** Processes the cover once per track (not on every playback tick). */
    private fun artFor(pkg: String, raw: Bitmap?): Art? {
        if (raw == null) return null
        artCache[pkg]?.let { if (it.source === raw) return it }
        return runCatching {
            val scaled = BitmapUtils.scaleToMax(raw, 320)
            Art(raw, scaled, BitmapUtils.blur(scaled), BitmapUtils.accent(scaled)).also { artCache[pkg] = it }
        }.getOrNull()
    }

    private fun scheduleWidgetUpdate() {
        val state = MediaRepository.spotify.value
        // Skip widget work when nothing the widget shows has changed (e.g. seek / position ticks).
        val key = state?.let { "${it.title}|${it.artist}|${it.isPlaying}|${System.identityHashCode(it.art)}" }
        if (key == lastWidgetKey) return
        lastWidgetKey = key
        widgetJob?.cancel()
        widgetJob = scope.launch {
            delay(60) // just enough to merge the title + cover callbacks of one track change
            WidgetStore.setNowPlaying(applicationContext, state)
            runCatching { PlaylistWidget().updateAll(applicationContext) }
            withContext(Dispatchers.IO) { WidgetStore.persist(applicationContext) }
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

        val isDoorbell = sbn.packageName in DoorbellStore.CAMERA_APPS
        val isMessage = n.category == Notification.CATEGORY_MESSAGE ||
            n.category == Notification.CATEGORY_EMAIL ||
            n.category == Notification.CATEGORY_CALL ||
            extras.containsKey(Notification.EXTRA_MESSAGES)
        if (!isMessage && !isDoorbell && !Prefs.island.value.showAllNotifications) return

        val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString() ?: return
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
        val picture = extractPicture(n, isDoorbell)

        // Apps often re-post the same notification; only pop when the content changed.
        // (Camera apps post first, then re-post once the snapshot is ready, so that counts as a change.)
        val fingerprint = "$title|$text|${picture != null}"
        if (lastShown[sbn.key] == fingerprint) return
        if (lastShown.size > 200) lastShown.clear()
        lastShown[sbn.key] = fingerprint

        val icon = runCatching {
            n.getLargeIcon()?.loadDrawable(this)?.toBitmap(96, 96)
                ?: packageManager.getApplicationIcon(sbn.packageName).toBitmap(96, 96)
        }.getOrNull()

        if (isDoorbell && picture != null) onDoorbellEvent(title, text, picture)

        MediaRepository.emitNotification(
            NotifEvent(
                key = sbn.key,
                packageName = sbn.packageName,
                appName = Apps.label(this, sbn.packageName),
                title = title,
                text = text,
                icon = icon,
                contentIntent = n.contentIntent,
                replyAction = findReplyAction(n),
                picture = picture,
            )
        )
    }

    /** The big picture of a notification; for camera apps also their large icon (often the snapshot). */
    @Suppress("DEPRECATION")
    private fun extractPicture(n: Notification, cameraApp: Boolean): Bitmap? {
        val extras = n.extras
        val bmp: Bitmap? = runCatching { extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE) }.getOrNull()
            ?: if (Build.VERSION.SDK_INT >= 31) {
                runCatching {
                    extras.getParcelable<android.graphics.drawable.Icon>(Notification.EXTRA_PICTURE_ICON)
                        ?.loadDrawable(this)?.toBitmap()
                }.getOrNull()
            } else null
        val picture = bmp ?: if (cameraApp) {
            runCatching { n.getLargeIcon()?.loadDrawable(this)?.toBitmap() }.getOrNull()
                ?.takeIf { it.width >= 200 } // a real snapshot, not a small avatar
        } else null
        return picture?.let { runCatching { BitmapUtils.scaleToMax(it, 720) }.getOrNull() }
    }

    private fun onDoorbellEvent(title: String, text: String, picture: Bitmap?) {
        scope.launch {
            withContext(Dispatchers.IO) { DoorbellStore.save(applicationContext, title, text, picture) }
            runCatching { DoorbellWidget().updateAll(applicationContext) }
            runCatching { PlaylistWidget().updateAll(applicationContext) }
            // The Spotify widget only shows the doorbell for a couple of minutes; switch it back after.
            delay(DoorbellStore.TAKEOVER_MS + 1_000)
            runCatching { PlaylistWidget().updateAll(applicationContext) }
        }
    }

    private fun findReplyAction(n: Notification): Notification.Action? {
        val actions = n.actions ?: return null
        val canType = { a: Notification.Action -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        if (Build.VERSION.SDK_INT >= 28) {
            actions.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY && canType(it) }
                ?.let { return it }
        }
        return actions.firstOrNull(canType)
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
