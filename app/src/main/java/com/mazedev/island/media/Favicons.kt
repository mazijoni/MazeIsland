package com.mazedev.island.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Website logos for browser media, cached on disk. */
object Favicons {
    fun load(context: Context, domain: String): Bitmap? {
        val dir = File(context.cacheDir, "favicons").apply { mkdirs() }
        val file = File(dir, domain.replace(Regex("[^a-z0-9.-]"), "_") + ".png")
        if (file.exists()) {
            BitmapFactory.decodeFile(file.path)?.let { return it }
        }
        // Google's favicon service returns the best icon the site has, up to the asked size.
        val bytes = runCatching { get("https://www.google.com/s2/favicons?domain=$domain&sz=256") }.getOrNull()
            ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (bmp.width < 32) return null // only the generic globe came back
        runCatching { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return bmp
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 6000
        c.setRequestProperty("User-Agent", "MazeIsland/0.1 (Android)")
        try {
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
