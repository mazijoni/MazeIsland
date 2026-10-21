package com.mazedev.island.media

import android.app.Notification
import android.app.PendingIntent
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
import com.mazedev.island.widget.WidgetPusher
import com.mazedev.island.widget.WidgetStore
import com.mazedev.island.widget.PlaylistLoader
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
        instance = this
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
        if (instance === this) instance = null
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
                override fun onQueueChanged(queue: MutableList<android.media.session.MediaSession.QueueItem>?) {
                    if (c.packageName == MediaRepository.SPOTIFY) updateNowList(force = true)
                }
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
        val art = artFor(c.packageName, raw) ?: fallbackArt(c.packageName, md)
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
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L),
            positionMs = state?.position ?: 0L,
            positionUpdatedAt = state?.lastPositionUpdateTime ?: 0L,
            speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
        )
    }

    /** Processes the cover once per track (not on every playback tick). */
    // ------------------------------------------------- fallback when there's no cover

    private val browserSite = HashMap<String, String>()
    private val fallbackCache = HashMap<String, Art>()
    private val faviconLoading = HashSet<String>()

    /**
     * No cover: use the website's logo for browser media (YouTube in Chrome etc.),
     * otherwise the app's own icon.
     */
    private fun fallbackArt(pkg: String, md: MediaMetadata): Art? {
        if (pkg in BROWSERS) {
            val domain = browserSite[pkg] ?: listOf(
                MediaMetadata.METADATA_KEY_ALBUM,
                MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
            ).firstNotNullOfOrNull { k -> md.getString(k)?.let { domainOf(it) } }
            if (domain != null) {
                fallbackCache["site:$domain"]?.let { return it }
                loadFavicon(domain)
            }
        }
        val key = "app:$pkg"
        fallbackCache[key]?.let { return it }
        return runCatching {
            val icon = packageManager.getApplicationIcon(pkg).toBitmap(256, 256)
            makeArt(icon)
        }.getOrNull()?.also { fallbackCache[key] = it }
    }

    private fun makeArt(bmp: Bitmap): Art {
        val scaled = BitmapUtils.scaleToMax(bmp, 320)
        return Art(bmp, scaled, BitmapUtils.blur(scaled), BitmapUtils.accent(scaled))
    }

    /** Fetches the site's logo once, then refreshes so it shows up. */
    private fun loadFavicon(domain: String) {
        if (!faviconLoading.add(domain)) return
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { Favicons.load(applicationContext, domain) }
            if (bmp != null) {
                fallbackCache["site:$domain"] = makeArt(bmp)
                refresh()
            } else {
                faviconLoading.remove(domain) // try again next time
            }
        }
    }

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
            runCatching { WidgetPusher.push(applicationContext) }
            withContext(Dispatchers.IO) { WidgetStore.persist(applicationContext) }
            updateNowList()
        }
    }

    // ------------------------------------- widget song list for what's playing

    private var nowListJob: kotlinx.coroutines.Job? = null
    private var lastLookedUpTrack: String? = null

    /**
     * The widget's song list for the song playing now: Spotify's own play queue when it shares
     * one, otherwise the album the song is on (looked up from Spotify's public pages).
     */
    private fun updateNowList(force: Boolean = false) {
        val ctx = applicationContext
        if (!WidgetStore.followNowPlaying(ctx)) return
        val c = callbacks.keys.firstOrNull { it.packageName == MediaRepository.SPOTIFY } ?: return
        nowListJob?.cancel()
        nowListJob = scope.launch {
            delay(400) // let the queue and metadata of a track change settle
            val queue = runCatching { c.queue }.getOrNull().orEmpty()
            if (queue.size >= 2) {
                val tracks = queue.mapNotNull { q ->
                    val d = q.description
                    val t = d.title?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    WidgetStore.Track("queue:${q.queueId}", t, d.subtitle?.toString().orEmpty())
                }.take(80)
                WidgetStore.saveNowList(ctx, "queue", c.queueTitle?.toString(), tracks)
                runCatching { WidgetPusher.push(ctx) }
                return@launch
            }
            val md = c.metadata ?: return@launch
            val ids = listOf(MediaMetadata.METADATA_KEY_MEDIA_ID, MediaMetadata.METADATA_KEY_MEDIA_URI)
                .mapNotNull { md.getString(it) }
            val trackId = ids.firstNotNullOfOrNull { Regex("""track[:/]([A-Za-z0-9]{10,})""").find(it)?.groupValues?.get(1) }
            if (trackId == null) {
                // Can't tell what's playing from: fall back to the saved playlist.
                if (WidgetStore.nowListKey(ctx) != null) {
                    WidgetStore.saveNowList(ctx, null, null, emptyList())
                    runCatching { WidgetPusher.push(ctx) }
                }
                return@launch
            }
            if (trackId == lastLookedUpTrack && !force) return@launch
            lastLookedUpTrack = trackId
            val albumId = withContext(Dispatchers.IO) { runCatching { PlaylistLoader.albumForTrack(trackId) }.getOrNull() }
            if (albumId != null && WidgetStore.nowListKey(ctx) == "album:$albumId") return@launch // same album
            val r = albumId?.let { id -> withContext(Dispatchers.IO) { runCatching { PlaylistLoader.fetchList("album", id) }.getOrNull() } }
            if (r != null && r.tracks.isNotEmpty()) {
                val name = r.name ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM)
                WidgetStore.saveNowList(ctx, "album:$albumId", name, r.tracks)
            } else {
                lastLookedUpTrack = null // try again on the next change
                WidgetStore.saveNowList(ctx, null, null, emptyList())
            }
            runCatching { WidgetPusher.push(ctx) }
        }
    }

    // -------------------------------------------------------- notifications

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        val extras = n.extras ?: return

        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
            // Music player notification. Browsers put the website ("youtube.com") in the sub text,
            // which we use to show the site's logo when there's no cover.
            if (sbn.packageName in BROWSERS) {
                extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.let { domainOf(it.toString()) }?.let {
                    if (browserSite[sbn.packageName] != it) {
                        browserSite[sbn.packageName] = it
                        refresh()
                    }
                }
            }
            return
        }

        // Timers, stopwatches, calls, navigation… become live activities in the island.
        if (Prefs.island.value.liveActivities) {
            val live = liveActivityFrom(sbn, n)
            if (live != null) {
                MediaRepository.putActivity(live)
                return
            }
        }
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
        sbn ?: return
        lastShown.remove(sbn.key)
        MediaRepository.removeActivity(sbn.key)
    }

    @Suppress("DEPRECATION")
    private fun liveActivityFrom(sbn: StatusBarNotification, n: Notification): LiveActivity? {
        val extras = n.extras
        val category = n.category
        val pkg = sbn.packageName
        var chrono = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        var countDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        var chronoBase = if (chrono) n.`when` else 0L
        val isCall = category == Notification.CATEGORY_CALL
        val isClock = pkg in CLOCK_APPS
        val title0 = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text0 = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val isGemini = pkg in GEMINI_APPS && sbn.isOngoing &&
            (isCall || "live" in title0.lowercase() || "live" in text0.lowercase())

        // The Clock app draws its timer in a custom layout, so the countdown isn't in the normal
        // fields. Render the notification's own view and read the timer from it.
        var pausedText: String? = null
        if (isClock && !chrono) {
            readChronometer(n)?.let { c ->
                chrono = true
                countDown = c.countDown
                chronoBase = c.wallBase
                if (c.paused) pausedText = c.text
            }
        }

        val kind = when {
            isGemini -> LiveActivity.Kind.GEMINI
            isCall -> LiveActivity.Kind.CALL
            category == Notification.CATEGORY_NAVIGATION -> LiveActivity.Kind.NAVIGATION
            category == Notification.CATEGORY_STOPWATCH || chrono -> LiveActivity.Kind.TIMER
            isClock && category == Notification.CATEGORY_ALARM -> LiveActivity.Kind.TIMER // timer done / alarm ringing
            else -> return null
        }
        if (!sbn.isOngoing && !isCall && !(isClock && category == Notification.CATEGORY_ALARM)) return null
        // Incoming call / timer done: a full-screen "ringing" intent. A call in progress shows its duration instead.
        val ringing = (isCall && n.fullScreenIntent != null && !chrono) ||
            (isClock && category == Notification.CATEGORY_ALARM)

        val title = title0.ifBlank {
            when (kind) {
                LiveActivity.Kind.GEMINI -> "Gemini Live"
                LiveActivity.Kind.TIMER -> if (ringing) "Time's up" else "Timer"
                else -> Apps.label(this, pkg)
            }
        }
        val icon = runCatching {
            (if (isCall) n.getLargeIcon()?.loadDrawable(this)?.toBitmap(96, 96) else null)
                ?: packageManager.getApplicationIcon(pkg).toBitmap(96, 96)
        }.getOrNull()

        // Calls (CallStyle) carry their answer / decline / hang-up intents in extras.
        fun intent(key: String) = runCatching { extras.getParcelable<PendingIntent>(key) }.getOrNull()
        val answer = intent("android.answerIntent")
        val decline = intent("android.declineIntent")
        val hangUp = intent("android.hangUpIntent")
        val actions = buildList {
            if (decline != null) add("Decline" to decline)
            if (hangUp != null) add("End" to hangUp)
            if (answer != null) add("Answer" to answer)
            if (isEmpty()) {
                n.actions?.take(3)?.forEach { a -> a.actionIntent?.let { add(a.title.toString() to it) } }
            }
        }
        return LiveActivity(
            key = sbn.key,
            packageName = pkg,
            appName = appNames.getOrPut(pkg) { Apps.label(this, pkg) },
            kind = kind,
            title = title,
            text = text0,
            icon = icon,
            chronoBase = if (chrono || (isCall && !ringing)) (if (chronoBase > 0) chronoBase else n.`when`) else 0L,
            countDown = countDown,
            accent = when {
                kind == LiveActivity.Kind.TIMER -> 0xFFFF9F0A.toInt()
                n.color != 0 -> n.color
                else -> 0xFFFF9F0A.toInt()
            },
            contentIntent = n.contentIntent,
            actions = actions,
            ringing = ringing,
            pausedText = pausedText,
        )
    }

    private class ChronoInfo(val wallBase: Long, val countDown: Boolean, val paused: Boolean, val text: String)

    /** Inflates the notification's views and finds the Chronometer the Clock app uses for timers. */
    private fun readChronometer(n: Notification): ChronoInfo? {
        @Suppress("DEPRECATION")
        val candidates = buildList {
            n.contentView?.let { add(it) }
            n.bigContentView?.let { add(it) }
            runCatching { Notification.Builder.recoverBuilder(this@MediaListenerService, n).createContentView() }
                .getOrNull()?.let { add(it) }
        }
        for (rv in candidates) {
            val root = runCatching { rv.apply(this, android.widget.FrameLayout(this)) }.getOrNull() ?: continue
            val chrono = findChronometer(root) ?: continue
            val texts = ArrayList<String>()
            collectTexts(root, texts)
            val paused = texts.any { it.contains("paused", ignoreCase = true) || it.contains("pause", ignoreCase = true) && it.length < 12 }
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            val countDown = if (Build.VERSION.SDK_INT >= 24) chrono.isCountDown else false
            val wall = System.currentTimeMillis() + (chrono.base - nowElapsed)
            return ChronoInfo(wall, countDown, paused, chrono.text?.toString().orEmpty())
        }
        return null
    }

    private fun findChronometer(v: android.view.View): android.widget.Chronometer? {
        if (v is android.widget.Chronometer) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findChronometer(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun collectTexts(v: android.view.View, out: MutableList<String>) {
        if (v is android.widget.TextView && v !is android.widget.Chronometer) v.text?.toString()?.let { if (it.isNotBlank()) out += it }
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) collectTexts(v.getChildAt(i), out)
    }

    companion object {
        private const val TAG = "MediaListener"

        @Volatile private var instance: MediaListenerService? = null

        /** Removes a notification for good (swiped away in the island). */
        fun dismissNotification(key: String) {
            runCatching { instance?.cancelNotification(key) }
        }

        /** Clock apps whose timers become live activities. */
        val CLOCK_APPS = setOf(
            "com.google.android.deskclock", "com.android.deskclock",
            "com.sec.android.app.clockpackage", "com.oneplus.deskclock",
        )

        /** Gemini (standalone app or inside the Google app). */
        val GEMINI_APPS = setOf("com.google.android.apps.bard", "com.google.android.googlequicksearchbox")

        val BROWSERS = setOf(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
            "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.focus",
            "com.brave.browser", "com.microsoft.emmx", "com.opera.browser", "com.opera.gx",
            "com.vivaldi.browser", "com.sec.android.app.sbrowser", "com.duckduckgo.mobile.android",
            "com.kiwibrowser.browser", "app.vanadium.browser",
        )

        private val DOMAIN = Regex("""(?i)\b((?:[a-z0-9-]+\.)+[a-z]{2,})\b""")

        /** "www.youtube.com" / "https://music.youtube.com/watch…" → "music.youtube.com". */
        fun domainOf(text: String): String? =
            DOMAIN.find(text)?.groupValues?.get(1)?.lowercase()?.removePrefix("www.")
                ?.takeIf { !it.endsWith(".mp3") && !it.endsWith(".mp4") }
        private val IGNORED_CATEGORIES = setOf(
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_STATUS,
        )
    }
}
