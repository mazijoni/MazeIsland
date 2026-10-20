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
 * Everything the widget shows, persisted to disk so it survives the app process being killed.
 * (Widgets are drawn from a snapshot, they can't hold live state.)
 */
object WidgetStore {
    private const val PREFS = "widget"
    private const val NOW_ART = "now_art.png"
    private const val NOW_BG = "now_bg.png"
    private const val PL_COVER = "playlist_cover.png"
    private const val PL_BG = "playlist_bg.png"

    @Volatile private var lastArt: Bitmap? = null

    data class Snapshot(
        val playlistId: String?,
        val playlistName: String?,
        val hasNowPlaying: Boolean,
        val title: String,
        val artist: String,
        val isPlaying: Boolean,
        val canPlayFromUri: Boolean,
        val art: Bitmap?,
        val background: Bitmap?,
    ) {
        val playlistUri: String? get() = playlistId?.let { "spotify:playlist:$it" }
    }

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

    fun saveNowPlaying(context: Context, state: MediaState?) {
        val e = prefs(context).edit()
        if (state == null) {
            e.putBoolean("hasNow", false)
        } else {
            e.putBoolean("hasNow", true)
                .putString("title", state.title)
                .putString("artist", state.artist)
                .putBoolean("playing", state.isPlaying)
                .putBoolean("fromUri", state.canPlayFromUri)
        }
        e.commit()

        val art = state?.art
        if (art !== lastArt) {
            lastArt = art
            if (art != null) {
                write(context, NOW_ART, art)
                write(context, NOW_BG, BitmapUtils.blur(art))
            } else {
                delete(context, NOW_ART)
                delete(context, NOW_BG)
            }
        }
    }

    fun load(context: Context): Snapshot {
        val p = prefs(context)
        val has = p.getBoolean("hasNow", false)
        val nowArt = if (has) read(context, NOW_ART) else null
        val bg = if (nowArt != null) read(context, NOW_BG) else read(context, PL_BG)
        return Snapshot(
            playlistId = p.getString("playlistId", null),
            playlistName = p.getString("playlistName", null),
            hasNowPlaying = has,
            title = p.getString("title", "") ?: "",
            artist = p.getString("artist", "") ?: "",
            isPlaying = has && p.getBoolean("playing", false),
            canPlayFromUri = p.getBoolean("fromUri", false),
            art = nowArt ?: read(context, PL_COVER),
            background = bg,
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
