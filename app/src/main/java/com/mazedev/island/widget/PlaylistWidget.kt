package com.mazedev.island.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.glance.LocalSize
import androidx.glance.layout.ColumnScope
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.mazedev.island.Prefs
import com.mazedev.island.R
import com.mazedev.island.media.MediaRepository
import com.mazedev.island.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlaylistWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlaylistWidget()
}

class PlaylistWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = withContext(Dispatchers.IO) {
            runCatching { PlaylistLoader.refreshTracksIfStale(context) }
            WidgetStore.load(context)
        }
        // Someone rang the doorbell in the last couple of minutes → show the camera snapshot instead.
        val door = if (Prefs.island.value.doorbellTakeover) {
            withContext(Dispatchers.IO) { DoorbellStore.recent(context) }
        } else null
        provideContent {
            if (door != null) DoorbellContent(context, door, recentBadge = true) else WidgetContent(context, snap)
        }
    }
}

private val White = ColorProvider(Color.White)
private val Dim = ColorProvider(Color(0xB3FFFFFF))

@Composable
private fun WidgetContent(context: Context, s: WidgetStore.Snapshot) {
    val openSettings: Action = actionStartActivity(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    val openPlaylist: Action = s.playlistId?.let { actionStartActivity(WidgetStore.playlistIntent(it)) } ?: openSettings

    // Spotify running → control it directly. Not running → open the playlist in Spotify.
    val playAction: Action = if (s.hasNowPlaying) actionRunCallback<PlayPauseAction>() else openPlaylist

    val heading = s.playlistName ?: "Tap to pick a playlist"
    val line1 = if (s.hasNowPlaying) s.title else (s.playlistName ?: "No playlist yet")
    val line2 = when {
        s.hasNowPlaying -> s.artist
        s.playlistId != null -> "Tap play to open in Spotify"
        else -> "Paste a link in Maze Island"
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(24.dp)
            .background(Color(0xFF141418))
            .clickable(if (s.playlistId == null) openSettings else openPlaylist),
    ) {
        // Blurred cover as the background
        s.background?.let {
            Image(
                provider = ImageProvider(it),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        // Dark scrim so text stays readable on bright covers
        Box(modifier = GlanceModifier.fillMaxSize().background(Color(0x59000000))) {}

        // Tall enough → the player on top and the playlist's songs underneath.
        val tall = LocalSize.current.height >= 200.dp && s.playlistId != null
        Column(modifier = GlanceModifier.fillMaxSize()) {
        Row(
            modifier = (if (tall) GlanceModifier.fillMaxWidth().height(118.dp) else GlanceModifier.fillMaxSize())
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Sharp cover
            Box(
                modifier = GlanceModifier
                    .size(88.dp)
                    .cornerRadius(16.dp)
                    .background(Color(0x1FFFFFFF))
                    .clickable(openPlaylist),
                contentAlignment = Alignment.Center,
            ) {
                if (s.art != null) {
                    Image(
                        provider = ImageProvider(s.art),
                        contentDescription = "Cover",
                        modifier = GlanceModifier.fillMaxSize().cornerRadius(16.dp),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Image(
                        provider = ImageProvider(R.drawable.ic_music),
                        contentDescription = null,
                        modifier = GlanceModifier.size(36.dp),
                        colorFilter = ColorFilter.tint(Dim),
                    )
                }
            }

            Spacer(modifier = GlanceModifier.width(14.dp))

            Column(
                modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = heading,
                    style = TextStyle(color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = line1,
                    style = TextStyle(color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Text(
                    text = line2,
                    style = TextStyle(color = Dim, fontSize = 13.sp),
                    maxLines = 1,
                )
                Spacer(modifier = GlanceModifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.hasNowPlaying) {
                        GlassButton(R.drawable.ic_prev, "Previous", actionRunCallback<PreviousAction>())
                        Spacer(modifier = GlanceModifier.width(8.dp))
                    }
                    GlassButton(
                        res = if (s.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                        description = if (s.isPlaying) "Pause" else "Play",
                        action = playAction,
                        size = 38.dp,
                        strong = true,
                    )
                    if (s.hasNowPlaying) {
                        Spacer(modifier = GlanceModifier.width(8.dp))
                        GlassButton(R.drawable.ic_next, "Next", actionRunCallback<NextAction>())
                    }
                    Spacer(modifier = GlanceModifier.width(12.dp))
                    // Always opens the chosen playlist in Spotify
                    GlassButton(R.drawable.ic_playlist, "Open playlist", openPlaylist)
                }
            }
        }
        if (tall) {
            TrackList(s)
        }
        }
    }
}

/** The playlist's songs; tap one to play it. The one playing now is highlighted. */
@Composable
private fun ColumnScope.TrackList(s: WidgetStore.Snapshot) {
    if (s.tracks.isEmpty()) {
        Box(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight().padding(10.dp)
                .cornerRadius(12.dp).background(Color(0x14FFFFFF))
                .clickable(actionRunCallback<ReloadTracksAction>()),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Couldn't load the songs yet · tap to try again",
                style = TextStyle(color = Dim, fontSize = 12.sp),
                maxLines = 2,
            )
        }
        return
    }
    LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight().padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
        items(s.tracks) { t ->
            val playing = s.hasNowPlaying && t.title.equals(s.title, ignoreCase = true)
            // Spotify running → start the song through it; otherwise open the song in Spotify.
            val action: Action = if (s.hasNowPlaying) {
                actionRunCallback<PlayTrackAction>(actionParametersOf(TrackUriKey to t.uri))
            } else {
                actionStartActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(t.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .cornerRadius(12.dp)
                    .background(if (playing) Color(0x33FFFFFF) else Color(0x14FFFFFF))
                    .clickable(action)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        t.title,
                        style = TextStyle(color = White, fontSize = 13.sp, fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium),
                        maxLines = 1,
                    )
                    if (t.artist.isNotBlank()) {
                        Text(t.artist, style = TextStyle(color = Dim, fontSize = 11.sp), maxLines = 1)
                    }
                }
                if (playing) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_music),
                        contentDescription = "Playing",
                        modifier = GlanceModifier.size(14.dp),
                        colorFilter = ColorFilter.tint(White),
                    )
                }
            }
        }
    }
}

private val TrackUriKey = ActionParameters.Key<String>("trackUri")

class ReloadTracksAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        kotlinx.coroutines.withContext(Dispatchers.IO) { PlaylistLoader.refreshTracksIfStale(context, force = true) }
        WidgetPusher.push(context)
    }
}

class PlayTrackAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val uri = parameters[TrackUriKey] ?: return
        if (!MediaRepository.playSpotifyUri(context, uri)) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

/** Soft, translucent "glass" button. */
@Composable
private fun GlassButton(
    res: Int,
    description: String,
    action: Action,
    size: Dp = 32.dp,
    strong: Boolean = false,
) {
    Box(
        modifier = GlanceModifier
            .size(size)
            .cornerRadius(size / 2)
            .background(if (strong) Color(0x38FFFFFF) else Color(0x1FFFFFFF))
            .clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(res),
            contentDescription = description,
            modifier = GlanceModifier.size(size * 0.5f),
            colorFilter = ColorFilter.tint(ColorProvider(Color(0xE6FFFFFF))),
        )
    }
}

// ------------------------------------------------------------ button actions

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        MediaRepository.playPause(context, MediaRepository.SPOTIFY)
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        MediaRepository.next(context, MediaRepository.SPOTIFY)
    }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        MediaRepository.previous(context, MediaRepository.SPOTIFY)
    }
}
