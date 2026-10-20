package com.mazedev.island.util

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap

data class AppEntry(val packageName: String, val label: String, val icon: ImageBitmap)

@Suppress("DEPRECATION")
object Apps {

    /** Every app with a launcher icon, sorted by name (for the menu picker). */
    fun launchable(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .mapNotNull { entry(context, it) }
            .sortedBy { it.label.lowercase() }
    }

    /** The chosen menu apps, in the order the user picked them. */
    fun load(context: Context, packages: List<String>): List<AppEntry> =
        packages.mapNotNull { entry(context, it) }

    private fun entry(context: Context, pkg: String): AppEntry? = runCatching {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(pkg, 0)
        AppEntry(
            packageName = pkg,
            label = pm.getApplicationLabel(info).toString(),
            icon = pm.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap(),
        )
    }.getOrNull()

    fun label(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun launch(context: Context, pkg: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}

object Permissions {
    fun notificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun accessibility(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.startsWith(context.packageName + "/") }
    }
}

/** Sends a PendingIntent (e.g. "open this chat") while allowing it to start an activity on Android 14+. */
fun PendingIntent.sendCompat() {
    try {
        if (Build.VERSION.SDK_INT >= 34) {
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            send(null, 0, null, null, null, null, options.toBundle())
        } else {
            send()
        }
    } catch (_: PendingIntent.CanceledException) {
    }
}
