package com.mazedev.island.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Turns a pasted Spotify playlist link into a saved playlist.
 * Name + cover come from Spotify's public oEmbed endpoint, so no login or API key is needed.
 */
object PlaylistLoader {
    private val ID = Regex("""playlist[/:]([A-Za-z0-9]{10,})""")

    sealed interface SaveResult {
        data class Ok(val name: String) : SaveResult
        data class Error(val message: String) : SaveResult
    }

    fun parseId(input: String): String? = ID.find(input.trim())?.groupValues?.get(1)

    suspend fun save(context: Context, link: String): SaveResult {
        val id = parseId(link)
            ?: return SaveResult.Error("That doesn't look like a playlist link. In Spotify: playlist → ⋯ → Share → Copy link.")
        val (name, cover) = withContext(Dispatchers.IO) { fetchInfo(id) }
        val finalName = name ?: "Playlist"
        withContext(Dispatchers.IO) {
            WidgetStore.savePlaylist(context, id, finalName, cover)
            fetchTracks(id)?.let { WidgetStore.saveTracks(context, it) }
        }
        runCatching { PlaylistWidget().updateAll(context) }
        return SaveResult.Ok(finalName)
    }

    /** Refreshes the song list if it's old (called when the widget redraws). */
    fun refreshTracksIfStale(context: Context) {
        val id = WidgetStore.playlistId(context) ?: return
        if (System.currentTimeMillis() - WidgetStore.tracksFetchedAt(context) < 6 * 3_600_000L) return
        fetchTracks(id)?.let { WidgetStore.saveTracks(context, it) }
    }

    /**
     * The playlist's songs, read from Spotify's public embed page (the same data the
     * "embed this playlist" player uses), so no login or API key is needed.
     */
    fun fetchTracks(id: String): List<WidgetStore.Track>? = try {
        val html = String(httpGet("https://open.spotify.com/embed/playlist/$id"))
        val json = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: throw IllegalStateException("no data")
        val entity = JSONObject(json).getJSONObject("props").getJSONObject("pageProps")
            .getJSONObject("state").getJSONObject("data").getJSONObject("entity")
        val list = entity.getJSONArray("trackList")
        (0 until minOf(list.length(), 60)).mapNotNull { i ->
            val t = list.getJSONObject(i)
            val uri = t.optString("uri").takeIf { it.startsWith("spotify:") } ?: return@mapNotNull null
            WidgetStore.Track(uri, t.optString("title"), t.optString("subtitle"))
        }
    } catch (_: Exception) {
        null
    }

    private fun fetchInfo(id: String): Pair<String?, Bitmap?> = try {
        val page = "https://open.spotify.com/playlist/$id"
        val json = JSONObject(String(httpGet("https://open.spotify.com/oembed?url=" + URLEncoder.encode(page, "UTF-8"))))
        val name = json.optString("title").takeIf { it.isNotBlank() }
        val thumb = json.optString("thumbnail_url").takeIf { it.isNotBlank() }
        val cover = thumb?.let { url ->
            runCatching { httpGet(url).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
        }
        name to cover
    } catch (_: Exception) {
        null to null
    }

    private fun httpGet(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "MazeIsland/0.1 (Android)")
        try {
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
