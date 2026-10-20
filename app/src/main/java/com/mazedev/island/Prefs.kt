package com.mazedev.island

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class IslandStyle {
    /** A black pill is always visible around the camera. */
    BAR,

    /** Nothing visible until something happens; then it grows out of the camera to the sides. */
    HIDDEN,
}

/** Island settings. Sizes and offsets are in dp. */
data class IslandConfig(
    val enabled: Boolean = true,
    val style: IslandStyle = IslandStyle.BAR,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val width: Float = 110f,
    val height: Float = 34f,
    val showAllNotifications: Boolean = false,
    val hideSystemPopups: Boolean = false,
    /** Gap between the expanded island and the screen edges. */
    val expandedMargin: Float = 8f,
    /** Corner radius ("bevel") of the expanded island. */
    val expandedCorner: Float = 30f,
    /** Use Material You themed icons (like the Pixel launcher's "Themed icons") in the app menu. */
    val themedIcons: Boolean = true,
    val menuApps: List<String> = listOf("com.spotify.music"),
)

object Prefs {
    private lateinit var sp: SharedPreferences
    private val _island = MutableStateFlow(IslandConfig())
    val island: StateFlow<IslandConfig> = _island.asStateFlow()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences("island", Context.MODE_PRIVATE)
        val d = IslandConfig()
        _island.value = IslandConfig(
            enabled = sp.getBoolean("enabled", d.enabled),
            style = runCatching { IslandStyle.valueOf(sp.getString("style", d.style.name)!!) }.getOrDefault(d.style),
            offsetX = sp.getFloat("offsetX", d.offsetX),
            offsetY = sp.getFloat("offsetY", d.offsetY),
            width = sp.getFloat("width", d.width),
            height = sp.getFloat("height", d.height),
            showAllNotifications = sp.getBoolean("showAll", d.showAllNotifications),
            hideSystemPopups = sp.getBoolean("hidePopups", d.hideSystemPopups),
            expandedMargin = sp.getFloat("expMargin", d.expandedMargin),
            expandedCorner = sp.getFloat("expCorner", d.expandedCorner),
            themedIcons = sp.getBoolean("themedIcons", d.themedIcons),
            menuApps = sp.getString("menuApps", null)
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?: d.menuApps,
        )
    }

    fun updateIsland(transform: (IslandConfig) -> IslandConfig) {
        val c = transform(_island.value)
        _island.value = c
        if (!::sp.isInitialized) return
        sp.edit()
            .putBoolean("enabled", c.enabled)
            .putString("style", c.style.name)
            .putFloat("offsetX", c.offsetX)
            .putFloat("offsetY", c.offsetY)
            .putFloat("width", c.width)
            .putFloat("height", c.height)
            .putBoolean("showAll", c.showAllNotifications)
            .putBoolean("hidePopups", c.hideSystemPopups)
            .putFloat("expMargin", c.expandedMargin)
            .putFloat("expCorner", c.expandedCorner)
            .putBoolean("themedIcons", c.themedIcons)
            .putString("menuApps", c.menuApps.joinToString(","))
            .apply()
    }
}

enum class PreviewMode { NONE, GLOW, EXPANDED }

/**
 * Live preview while adjusting the island in Settings: a white glow so you can see the pill,
 * and the island held open while changing the expanded-card sliders.
 */
object IslandPreview {
    private val scope = MainScope()
    private var releaseJob: Job? = null
    private val _mode = MutableStateFlow(PreviewMode.NONE)
    val mode: StateFlow<PreviewMode> = _mode.asStateFlow()

    /** Call on every slider change. */
    fun show(mode: PreviewMode) {
        releaseJob?.cancel()
        _mode.value = mode
    }

    /** Call when the finger lifts; the preview fades out shortly after. */
    fun release(afterMs: Long = 1_500) {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(afterMs)
            _mode.value = PreviewMode.NONE
        }
    }
}
