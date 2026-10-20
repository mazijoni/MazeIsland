package com.mazedev.island.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Last doorbell / camera event, taken from the Google Home app's notifications
 * (they carry a snapshot picture). No Google account linking or API keys needed.
 */
object DoorbellStore {
    /** Google Home, plus the older Nest app. */
    val CAMERA_APPS = setOf("com.google.android.apps.chromecast.app", "com.nest.android")

    /** How long the Spotify widget shows the doorbell after a ring. */
    const val TAKEOVER_MS = 2 * 60_000L

    private const val PREFS = "doorbell"
    private const val SNAPSHOT = "doorbell.jpg"

    @Volatile private var memSnapshot: Bitmap? = null

    data class Event(val title: String, val text: String, val time: Long, val snapshot: Bitmap?)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, title: String, text: String, snapshot: Bitmap?) {
        prefs(context).edit()
            .putString("title", title)
            .putString("text", text)
            .putLong("time", System.currentTimeMillis())
            .commit()
        memSnapshot = snapshot
        val f = File(context.filesDir, SNAPSHOT)
        if (snapshot != null) {
            runCatching { f.outputStream().use { snapshot.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
        } else {
            f.delete()
        }
    }

    fun latest(context: Context): Event? {
        val p = prefs(context)
        val time = p.getLong("time", 0L)
        if (time == 0L) return null
        val snap = memSnapshot ?: File(context.filesDir, SNAPSHOT).takeIf { it.exists() }
            ?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
            ?.also { memSnapshot = it }
        return Event(p.getString("title", "") ?: "", p.getString("text", "") ?: "", time, snap)
    }

    /** The latest event, only if it happened within [TAKEOVER_MS]. */
    fun recent(context: Context): Event? =
        latest(context)?.takeIf { System.currentTimeMillis() - it.time < TAKEOVER_MS }

    /** Opens the Google Home app (where the live camera view is). */
    fun openAppIntent(context: Context): Intent? =
        CAMERA_APPS.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
