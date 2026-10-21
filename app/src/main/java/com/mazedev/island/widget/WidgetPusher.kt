package com.mazedev.island.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.updateAll

/**
 * Pushes a freshly drawn widget straight to the home screen. The normal Glance update goes
 * through a background job that can take a few seconds to start, which made song changes lag.
 */
object WidgetPusher {
    @OptIn(ExperimentalGlanceApi::class)
    suspend fun push(context: Context) {
        val awm = AppWidgetManager.getInstance(context)
        val glance = GlanceAppWidgetManager(context)
        val ids = runCatching { glance.getGlanceIds(PlaylistWidget::class.java) }.getOrDefault(emptyList())
        if (ids.isEmpty()) return
        val widget = PlaylistWidget()
        var failed = false
        for (id in ids) {
            runCatching {
                val appWidgetId = glance.getAppWidgetId(id)
                val options = awm.getAppWidgetOptions(appWidgetId)
                // Portrait size: min width, max height (what the launcher shows on a phone).
                val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
                val views = widget.compose(context, id, options, DpSize(w.dp, h.dp))
                awm.updateAppWidget(appWidgetId, views)
            }.onFailure { failed = true }
        }
        if (failed) runCatching { widget.updateAll(context) }
    }
}
