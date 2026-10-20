package com.mazedev.island.util

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Turns Android's own notification pop-ups (heads-up banners) on or off, so the island replaces them.
 * Needs WRITE_SECURE_SETTINGS, which can only be granted once from a computer:
 *   adb shell pm grant com.mazedev.island android.permission.WRITE_SECURE_SETTINGS
 * Notifications still arrive in the shade and on the lock screen as normal.
 */
object HeadsUp {
    private const val KEY = "heads_up_notifications_enabled"

    fun grantCommand(context: Context) =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    fun canControl(context: Context): Boolean =
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    /** Returns true if the system setting was changed. */
    fun setSystemPopups(context: Context, enabled: Boolean): Boolean {
        if (!canControl(context)) return false
        return runCatching {
            Settings.Global.putInt(context.contentResolver, KEY, if (enabled) 1 else 0)
        }.getOrDefault(false)
    }
}
