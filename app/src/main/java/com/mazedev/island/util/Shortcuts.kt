package com.mazedev.island.util

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.DrawableRes
import com.mazedev.island.R

/** What double-tapping the collapsed island does. */
enum class Shortcut(val label: String, @DrawableRes val icon: Int, val minSdk: Int = 26) {
    NONE("Nothing", R.drawable.ic_dnd),
    FLASHLIGHT("Flashlight (with brightness slider)", R.drawable.ic_flashlight),
    SCREENSHOT("Screenshot", R.drawable.ic_screenshot, minSdk = 28),
    LOCK("Lock screen", R.drawable.ic_lock, minSdk = 28),
    CAMERA("Open camera", R.drawable.ic_camera),
    PLAY_PAUSE("Play / pause", R.drawable.ic_play),
    DND("Do Not Disturb", R.drawable.ic_dnd),
    RINGER("Sound / vibrate", R.drawable.ic_bell),
    NOTIFICATIONS("Notification shade", R.drawable.ic_bell),
    QUICK_SETTINGS("Quick settings", R.drawable.ic_settings),
    RECENTS("Recent apps", R.drawable.ic_recents),
    ASSISTANT("Assistant", R.drawable.ic_assistant),
    APP_MENU("Open app menu", R.drawable.ic_apps),
    ;

    val available: Boolean get() = Build.VERSION.SDK_INT >= minSdk

    companion object {
        fun from(name: String): Shortcut = runCatching { valueOf(name) }.getOrDefault(NONE)
    }
}

object ShortcutRunner {
    /** Runs a shortcut that doesn't need the island UI. Returns a short label for feedback, or null. */
    fun run(service: AccessibilityService, s: Shortcut): String? {
        val ctx = service.applicationContext
        return when (s) {
            Shortcut.SCREENSHOT -> if (Build.VERSION.SDK_INT >= 28) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT); null
            } else null
            Shortcut.LOCK -> if (Build.VERSION.SDK_INT >= 28) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN); null
            } else null
            Shortcut.NOTIFICATIONS -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); null
            }
            Shortcut.QUICK_SETTINGS -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS); null
            }
            Shortcut.RECENTS -> {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); null
            }
            Shortcut.CAMERA -> {
                runCatching {
                    service.startActivity(
                        Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                null
            }
            Shortcut.ASSISTANT -> {
                runCatching {
                    service.startActivity(Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                null
            }
            Shortcut.PLAY_PAUSE -> {
                com.mazedev.island.media.MediaRepository.playPause(ctx); null
            }
            Shortcut.DND -> {
                if (Controls.toggleDnd(ctx)) null
                else {
                    runCatching { service.startActivity(Controls.policyAccessIntent()) }; null
                }
            }
            Shortcut.RINGER -> {
                Controls.cycleRinger(ctx); null
            }
            // These need the island itself; IslandService handles them.
            Shortcut.FLASHLIGHT, Shortcut.APP_MENU, Shortcut.NONE -> null
        }
    }
}
