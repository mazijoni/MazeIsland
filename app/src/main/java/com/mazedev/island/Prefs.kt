package com.mazedev.island

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Island settings. Sizes and offsets are in dp. */
data class IslandConfig(
    val enabled: Boolean = true,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val width: Float = 110f,
    val height: Float = 34f,
    val showAllNotifications: Boolean = false,
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
            offsetX = sp.getFloat("offsetX", d.offsetX),
            offsetY = sp.getFloat("offsetY", d.offsetY),
            width = sp.getFloat("width", d.width),
            height = sp.getFloat("height", d.height),
            showAllNotifications = sp.getBoolean("showAll", d.showAllNotifications),
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
            .putFloat("offsetX", c.offsetX)
            .putFloat("offsetY", c.offsetY)
            .putFloat("width", c.width)
            .putFloat("height", c.height)
            .putBoolean("showAll", c.showAllNotifications)
            .putString("menuApps", c.menuApps.joinToString(","))
            .apply()
    }
}
