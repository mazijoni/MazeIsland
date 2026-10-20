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
        withContext(Dispatchers.IO) { WidgetStore.savePlaylist(context, id, finalName, cover) }
        runCatching { PlaylistWidget().updateAll(context) }
        return SaveResult.Ok(finalName)
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
