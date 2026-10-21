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

    /** Refreshes the song list if it's old or missing (called when the widget redraws). */
    fun refreshTracksIfStale(context: Context, force: Boolean = false) {
        val id = WidgetStore.playlistId(context) ?: return
        val age = System.currentTimeMillis() - WidgetStore.tracksFetchedAt(context)
        val have = WidgetStore.tracks(context).isNotEmpty()
        // Retry a failed load after a minute; refresh a good list every 6 hours.
        if (!force && ((have && age < 6 * 3_600_000L) || (!have && age < 60_000L))) return
        val tracks = fetchTracks(id)
        WidgetStore.saveTracks(context, tracks ?: emptyList())
    }

    /**
     * The playlist's songs, read from Spotify's public embed page (the same data the
     * "embed this playlist" player uses), so no login or API key is needed.
     */
    fun fetchTracks(id: String): List<WidgetStore.Track>? = try {
        val html = String(httpGet("https://open.spotify.com/embed/playlist/$id"))
        val json = Regex("""<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: throw IllegalStateException("no data")
        // Find the song list wherever it is in the page data (its exact place has moved before).
        val out = ArrayList<WidgetStore.Track>()
        collectTracks(org.json.JSONTokener(json).nextValue(), out)
        out.distinctBy { it.uri }.take(60).takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /** Walks the JSON looking for objects that look like songs (a spotify:track uri + a title). */
    private fun collectTracks(node: Any?, out: MutableList<WidgetStore.Track>) {
        when (node) {
            is JSONObject -> {
                val uri = node.optString("uri")
                val title = node.optString("title").ifBlank { node.optString("name") }
                if ((uri.startsWith("spotify:track:") || uri.startsWith("spotify:episode:")) && title.isNotBlank()) {
                    val artist = node.optString("subtitle").ifBlank {
                        node.optJSONArray("artists")?.let { arr ->
                            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }.joinToString(", ")
                        }.orEmpty()
                    }
                    out += WidgetStore.Track(uri, title, artist)
                    return
                }
                node.keys().forEach { k -> collectTracks(node.opt(k), out) }
            }
            is org.json.JSONArray -> for (i in 0 until node.length()) collectTracks(node.opt(i), out)
        }
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
        // A normal browser user agent, so Spotify serves the regular page with its data.
        c.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
        )
        c.setRequestProperty("Accept-Language", "en")
        try {
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
