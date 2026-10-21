package com.mazedev.island.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mazedev.island.media.MediaState
import com.mazedev.island.util.BitmapUtils
import java.io.File

/**
 * Everything the widget shows. Kept in memory for instant updates, and mirrored to disk so it
 * survives the app process being killed. (Widgets are drawn from a snapshot, not live state.)
 */
object WidgetStore {
    private const val PREFS = "widget"
    private const val NOW_ART = "now_art.png"
    private const val NOW_BG = "now_bg.png"
    private const val PL_COVER = "playlist_cover.png"
    private const val PL_BG = "playlist_bg.png"

    // In-memory copies of the current cover; null = not loaded yet (fall back to disk).
    @Volatile private var memArt: Bitmap? = null
    @Volatile private var memBg: Bitmap? = null
    @Volatile private var memLoaded = false
    @Volatile private var dirty = false

    data class Track(val uri: String, val title: String, val artist: String)

    fun saveTracks(context: Context, tracks: List<Track>, error: String? = null) {
        val arr = org.json.JSONArray()
        tracks.forEach { arr.put(org.json.JSONObject().put("u", it.uri).put("t", it.title).put("a", it.artist)) }
        prefs(context).edit()
            .putString("tracks", arr.toString())
            .putLong("tracksAt", System.currentTimeMillis())
            .putString("tracksError", error)
            .apply()
    }

    /** Song list follows what's playing (its queue / album) instead of the saved playlist. */
    fun followNowPlaying(context: Context): Boolean = prefs(context).getBoolean("followNow", true)

    fun setFollowNowPlaying(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("followNow", on).apply()
    }

    /** The list for the song playing now: what it's from ([key], e.g. "album:ID"), its name and songs. */
    fun saveNowList(context: Context, key: String?, name: String?, tracks: List<Track>) {
        val arr = org.json.JSONArray()
        tracks.forEach { arr.put(org.json.JSONObject().put("u", it.uri).put("t", it.title).put("a", it.artist)) }
        prefs(context).edit()
            .putString("nowListKey", key)
            .putString("nowListName", name)
            .putString("nowList", arr.toString())
            .apply()
    }

    fun nowListKey(context: Context): String? = prefs(context).getString("nowListKey", null)

    private fun nowList(context: Context): List<Track> = runCatching {
        val arr = org.json.JSONArray(prefs(context).getString("nowList", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Track(o.getString("u"), o.optString("t"), o.optString("a"))
        }
    }.getOrDefault(emptyList())

    /** Why the last song-list load failed (shown in the widget), or null. */
    fun tracksError(context: Context): String? = prefs(context).getString("tracksError", null)

    fun renamePlaylist(context: Context, name: String) {
        prefs(context).edit().putString("playlistName", name).apply()
    }

    fun tracksFetchedAt(context: Context): Long = prefs(context).getLong("tracksAt", 0L)

    fun tracks(context: Context): List<Track> = runCatching {
        val arr = org.json.JSONArray(prefs(context).getString("tracks", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Track(o.getString("u"), o.optString("t"), o.optString("a"))
        }
    }.getOrDefault(emptyList())

    data class Snapshot(
        val playlistId: String?,
        val playlistName: String?,
        val hasNowPlaying: Boolean,
        val title: String,
        val artist: String,
        val isPlaying: Boolean,
        val art: Bitmap?,
        val background: Bitmap?,
        val tracks: List<Track> = emptyList(),
        val tracksError: String? = null,
        /** Name of the list shown (album / playlist of the song playing), null = the saved playlist. */
        val listName: String? = null,
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun playlistName(context: Context): String? = prefs(context).getString("playlistName", null)
    fun playlistId(context: Context): String? = prefs(context).getString("playlistId", null)
    fun playlistCover(context: Context): Bitmap? = read(context, PL_COVER)

    /** Opens the playlist in Spotify (or the browser if Spotify isn't installed). */
    fun playlistIntent(playlistId: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/playlist/$playlistId"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun savePlaylist(context: Context, id: String, name: String, cover: Bitmap?) {
        prefs(context).edit()
            .putString("playlistId", id)
            .putString("playlistName", name)
            .commit()
        if (cover != null) {
            write(context, PL_COVER, BitmapUtils.scaleToMax(cover, 320))
            write(context, PL_BG, BitmapUtils.blur(cover))
        } else {
            delete(context, PL_COVER)
            delete(context, PL_BG)
        }
    }

    /** Fast path: updates text instantly and keeps the cover in memory. Call [persist] afterwards. */
    fun setNowPlaying(context: Context, state: MediaState?) {
        val e = prefs(context).edit()
        if (state == null) {
            e.putBoolean("hasNow", false)
        } else {
            e.putBoolean("hasNow", true)
                .putString("title", state.title)
                .putString("artist", state.artist)
                .putBoolean("playing", state.isPlaying)
        }
        e.apply() // in-memory immediately, written to disk in the background

        val art = state?.art
        if (art !== memArt || !memLoaded) {
            memArt = art
            memBg = state?.blurred ?: art?.let { BitmapUtils.blur(it) }
            memLoaded = true
            dirty = true
        }
    }

    /** Slow path: mirror the in-memory cover to disk. */
    fun persist(context: Context) {
        if (!dirty) return
        dirty = false
        val art = memArt
        val bg = memBg
        if (art != null) write(context, NOW_ART, art) else delete(context, NOW_ART)
        if (bg != null) write(context, NOW_BG, bg) else delete(context, NOW_BG)
    }

    fun load(context: Context): Snapshot {
        val p = prefs(context)
        val has = p.getBoolean("hasNow", false)
        val nowArt = if (!has) null else if (memLoaded) memArt else read(context, NOW_ART)
        val nowBg = if (nowArt == null) null else if (memLoaded) memBg else read(context, NOW_BG)
        val nowTracks = if (has && p.getBoolean("followNow", true)) nowList(context) else emptyList()
        return Snapshot(
            playlistId = p.getString("playlistId", null),
            playlistName = p.getString("playlistName", null),
            hasNowPlaying = has,
            title = p.getString("title", "") ?: "",
            artist = p.getString("artist", "") ?: "",
            isPlaying = has && p.getBoolean("playing", false),
            art = nowArt ?: read(context, PL_COVER),
            background = nowBg ?: read(context, PL_BG),
            tracks = nowTracks.ifEmpty { tracks(context) },
            tracksError = p.getString("tracksError", null),
            listName = if (nowTracks.isNotEmpty()) p.getString("nowListName", null) else null,
        )
    }

    private fun write(context: Context, name: String, bmp: Bitmap) {
        runCatching {
            File(context.filesDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun read(context: Context, name: String): Bitmap? {
        val f = File(context.filesDir, name)
        return if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
    }

    private fun delete(context: Context, name: String) {
        File(context.filesDir, name).delete()
    }
}
