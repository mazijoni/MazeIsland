package com.mazedev.island.island

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.mazedev.island.util.Permissions
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Videos the user asked to play in the island (from the share menu). */
object VideoRequests {
    private val _requests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val requests: SharedFlow<String> = _requests.asSharedFlow()

    fun play(videoId: String) {
        _requests.tryEmit(videoId)
    }

    private val ID = Regex(
        """(?:youtu\.be/|youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|live/|embed/|v/))([A-Za-z0-9_-]{11})"""
    )

    /** Finds a YouTube video ID in shared text ("https://youtu.be/dQw4w9WgXcQ?si=…" etc.). */
    fun parseId(text: String): String? = ID.find(text)?.groupValues?.get(1)
}

/**
 * Shows up as "Play in island" in the share menu. Takes the shared YouTube link,
 * hands it to the island and closes right away.
 */
class ShareToIslandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent?.dataString
            ?: ""
        val id = VideoRequests.parseId(text)
        when {
            id == null -> Toast.makeText(this, "That isn't a YouTube video link", Toast.LENGTH_SHORT).show()
            !Permissions.accessibility(this) -> Toast.makeText(
                this, "Turn on the Maze Island accessibility service first", Toast.LENGTH_LONG,
            ).show()
            else -> VideoRequests.play(id)
        }
        finish()
    }
}
