package com.mazedev.island.island

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/** WebView hosting YouTube's official embedded player (IFrame player). */
object YouTubeWebView {
    // YouTube's embed needs a real page origin/referrer, so the player page is given one.
    private const val ORIGIN = "https://maze-development.com"

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context, videoId: String): WebView = WebView(context).apply {
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            // Links inside the player (title, YouTube logo) open in the YouTube app instead.
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.contains("/embed/")) return false
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                return true
            }
        }
        val html = """
            <!doctype html>
            <html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="referrer" content="strict-origin-when-cross-origin">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
            iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style>
            </head><body>
            <iframe
              src="https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&rel=0&modestbranding=1&origin=$ORIGIN"
              allow="autoplay; encrypted-media; picture-in-picture"
              referrerpolicy="strict-origin-when-cross-origin"
              allowfullscreen></iframe>
            </body></html>
        """.trimIndent()
        loadDataWithBaseURL("$ORIGIN/", html, "text/html", "utf-8", null)
    }
}
