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
        val shownName = withContext(Dispatchers.IO) {
            WidgetStore.savePlaylist(context, id, finalName, cover)
            val r = fetchTracks(id)
            WidgetStore.saveTracks(context, r.tracks, r.error)
            if (name == null && r.name != null) WidgetStore.renamePlaylist(context, r.name)
            if (name == null) r.name ?: finalName else finalName
        }
        runCatching { PlaylistWidget().updateAll(context) }
        return SaveResult.Ok(shownName)
    }

    /** Refreshes the song list if it's old or missing (called when the widget redraws). */
    fun refreshTracksIfStale(context: Context, force: Boolean = false) {
        val id = WidgetStore.playlistId(context) ?: return
        val age = System.currentTimeMillis() - WidgetStore.tracksFetchedAt(context)
        val have = WidgetStore.tracks(context).isNotEmpty()
        // Retry a failed load after a minute; refresh a good list every 6 hours.
        if (!force && ((have && age < 6 * 3_600_000L) || (!have && age < 60_000L))) return
        val r = fetchTracks(id)
        WidgetStore.saveTracks(context, r.tracks, r.error)
        // The name lookup failed when the playlist was added: take it from the song data instead.
        if (r.name != null && WidgetStore.playlistName(context).let { it == null || it == "Playlist" }) {
            WidgetStore.renamePlaylist(context, r.name)
        }
    }

    data class TrackResult(val tracks: List<WidgetStore.Track>, val name: String?, val error: String?)

    /**
     * The playlist's songs, read from Spotify's public embed page (the same data the
     * "embed this playlist" player uses), so no login or API key is needed.
     * Tries a few ways to read the page, since Spotify changes its layout now and then.
     */
    fun fetchTracks(id: String): TrackResult {
        var lastError = "no connection"
        for (ua in listOf(MOBILE_UA, DESKTOP_UA)) {
            val html = try {
                String(httpGet("https://open.spotify.com/embed/playlist/$id", ua))
            } catch (e: HttpError) {
                lastError = if (e.code == 404) "playlist not found or private (make it public in Spotify)"
                else "Spotify said ${e.code}"
                if (e.code == 404) break else continue
            } catch (e: Exception) {
                lastError = "no connection"
                continue
            }
            val out = ArrayList<WidgetStore.Track>()
            var name: String? = null
            // 1) The page's JSON data block.
            Regex("""<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1)?.let { json ->
                    runCatching {
                        val root = org.json.JSONTokener(json).nextValue()
                        collectTracks(root, out)
                        name = findEntityName(root)
                    }
                }
            // 2) Anywhere else in the page (newer pages stream the data in other script tags,
            //    sometimes with escaped quotes).
            if (out.isEmpty()) scanRaw(html.replace("\\\"", "\"").replace("\\u0026", "&"), out)
            if (name == null) {
                name = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)""")
                    .find(html)?.groupValues?.get(1)?.let(::unescapeHtml)
                    ?: Regex("""<title>([^<]+)</title>""").find(html)?.groupValues?.get(1)
                        ?.substringBefore(" | Spotify")?.substringBefore(" - playlist")?.let(::unescapeHtml)
                        ?.takeIf { it.isNotBlank() && !it.equals("Spotify", true) }
            }
            val tracks = out.distinctBy { it.uri }.take(60)
            if (tracks.isNotEmpty()) return TrackResult(tracks, name, null)
            lastError = "couldn't read the song list"
        }
        return TrackResult(emptyList(), null, lastError)
    }

    /** Song objects written straight into the page: "uri":"spotify:track:…" with a title nearby. */
    private fun scanRaw(html: String, out: MutableList<WidgetStore.Track>) {
        val uriRe = Regex(""""uri"\s*:\s*"(spotify:(?:track|episode):[A-Za-z0-9]+)"""")
        val titleRe = Regex(""""(?:title|name)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val subRe = Regex(""""subtitle"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        for (m in uriRe.findAll(html)) {
            val from = (m.range.first - 500).coerceAtLeast(0)
            val to = (m.range.last + 500).coerceAtMost(html.length)
            val window = html.substring(from, to)
            val center = m.range.first - from
            val title = titleRe.findAll(window).minByOrNull { kotlin.math.abs(it.range.first - center) }
                ?.groupValues?.get(1)?.let(::unescapeJson) ?: continue
            val artist = subRe.findAll(window).minByOrNull { kotlin.math.abs(it.range.first - center) }
                ?.groupValues?.get(1)?.let(::unescapeJson).orEmpty()
            out += WidgetStore.Track(m.groupValues[1], title, artist)
        }
    }

    /** The playlist's own name in the page data (the object that holds the track list). */
    private fun findEntityName(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                if (node.has("trackList")) {
                    node.optString("name").ifBlank { node.optString("title") }.takeIf { it.isNotBlank() }?.let { return it }
                }
                for (k in node.keys()) findEntityName(node.opt(k))?.let { return it }
            }
            is org.json.JSONArray -> for (i in 0 until node.length()) findEntityName(node.opt(i))?.let { return it }
        }
        return null
    }

    private fun unescapeJson(s: String): String = runCatching { org.json.JSONTokener("\"$s\"").nextValue() as String }.getOrDefault(s)

    private fun unescapeHtml(s: String): String =
        s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")

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

    private class HttpError(val code: Int) : Exception("HTTP $code")

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private fun httpGet(url: String, userAgent: String = MOBILE_UA): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.instanceFollowRedirects = true
        // A normal browser user agent, so Spotify serves the regular page with its data.
        c.setRequestProperty("User-Agent", userAgent)
        c.setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
        c.setRequestProperty("Accept-Language", "en")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw HttpError(code)
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
