package com.mazedev.island.widget

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.mazedev.island.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DoorbellWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DoorbellWidget()
}

/** Shows the last doorbell snapshot from Google Home. Tap to open the live view in Google Home. */
class DoorbellWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val event = withContext(Dispatchers.IO) { DoorbellStore.latest(context) }
        provideContent { DoorbellContent(context, event, recentBadge = false) }
    }
}

private val TextWhite = ColorProvider(Color.White)
private val TextDim = ColorProvider(Color(0xB3FFFFFF))

/** Shared with the Spotify widget's 2-minute "someone's at the door" takeover. */
@Composable
internal fun DoorbellContent(context: Context, event: DoorbellStore.Event?, recentBadge: Boolean) {
    val open: Action = actionStartActivity(
        DoorbellStore.openAppIntent(context)
            ?: Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(24.dp)
            .background(Color(0xFF141418))
            .clickable(open),
        contentAlignment = Alignment.BottomStart,
    ) {
        event?.snapshot?.let {
            Image(
                provider = ImageProvider(it),
                contentDescription = "Doorbell snapshot",
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(GlanceModifier.fillMaxSize().background(Color(0x33000000))) {}
        }
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (event == null) {
                Text(
                    "Doorbell",
                    style = TextStyle(color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                )
                Text(
                    "Rings and camera alerts from the Google Home app will show here.",
                    style = TextStyle(color = TextDim, fontSize = 12.sp),
                    maxLines = 2,
                )
            } else {
                val time = DateFormat.getTimeFormat(context).format(java.util.Date(event.time))
                Text(
                    (if (recentBadge) "🔔 " else "") + event.title.ifBlank { "Doorbell" },
                    style = TextStyle(color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    listOf(event.text, time).filter { it.isNotBlank() }.joinToString(" · "),
                    style = TextStyle(color = TextDim, fontSize = 12.sp),
                    maxLines = 1,
                )
            }
        }
    }
}
