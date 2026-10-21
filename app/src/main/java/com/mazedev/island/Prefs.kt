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
/** True when the island is drawn under the real status bar (setting on + permission granted). */
fun IslandConfig.underStatusBar(context: Context): Boolean =
    realStatusBar && android.provider.Settings.canDrawOverlays(context)

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
    /** App tab look. */
    val appIconSize: Float = 44f,
    val appColumns: Int = 5,
    val appLabels: Boolean = true,
    val appBrightness: Float = 1f,
    val appGrayscale: Boolean = false,
    /** Spotify widget switches to the doorbell snapshot for a couple of minutes after a ring. */
    val doorbellTakeover: Boolean = true,
    /** Quick controls in the app tab, in display order (ControlKind names). */
    val controls: List<String> = listOf("BRIGHTNESS", "FLASHLIGHT", "DND"),
    val showSettingsTile: Boolean = true,
    /** What double-tapping the collapsed island does (Shortcut name). */
    val doubleTap: String = "FLASHLIGHT",
    /** Timers, calls, navigation… from ongoing notifications. */
    val liveActivities: Boolean = true,
    /** iOS-style system alerts. */
    val alertCharging: Boolean = true,
    val alertLowBattery: Boolean = true,
    val alertRinger: Boolean = true,
    val alertDnd: Boolean = true,
    val alertUnlock: Boolean = false,
    val alertBluetooth: Boolean = true,
    /** Draw a clock + battery beside the camera while the island is open (it covers the real ones). */
    val statusInIsland: Boolean = true,
    /** Nudges that clock/battery row up or down (dp), to line up with the real status bar. */
    val statusOffsetY: Float = 2f,
    /** Draw the island underneath Android's status bar so the real clock/battery show on top. */
    val realStatusBar: Boolean = false,
    /** Optional tabs in the open island. */
    val tabWeather: Boolean = false,
    val tabContacts: Boolean = false,
    val tabCamera: Boolean = false,
    /** Weather for this city instead of the phone's location (blank = use location). */
    val weatherCity: String = "",
    /** Everything except music and charging in white/gray instead of colors. */
    val monochrome: Boolean = false,
    /** Where the front camera is: CENTER, LEFT or RIGHT. */
    val cameraPosition: String = "CENTER",
    /** Look of the clock/battery row in the open island: IOS, PIXEL or SAMSUNG. */
    val statusStyle: String = "PIXEL",
    val statusShowSignal: Boolean = true,
    val statusShowWifi: Boolean = true,
    val statusShowBluetooth: Boolean = true,
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
            appIconSize = sp.getFloat("appIconSize", d.appIconSize),
            appColumns = sp.getInt("appColumns", d.appColumns),
            appLabels = sp.getBoolean("appLabels", d.appLabels),
            appBrightness = sp.getFloat("appBrightness", d.appBrightness),
            appGrayscale = sp.getBoolean("appGrayscale", d.appGrayscale),
            doorbellTakeover = sp.getBoolean("doorbellTakeover", d.doorbellTakeover),
            controls = sp.getString("controls", null)?.split(",")?.filter { it.isNotBlank() } ?: d.controls,
            showSettingsTile = sp.getBoolean("showSettingsTile", d.showSettingsTile),
            doubleTap = sp.getString("doubleTap", d.doubleTap) ?: d.doubleTap,
            liveActivities = sp.getBoolean("liveActivities", d.liveActivities),
            alertCharging = sp.getBoolean("alertCharging", d.alertCharging),
            alertLowBattery = sp.getBoolean("alertLowBattery", d.alertLowBattery),
            alertRinger = sp.getBoolean("alertRinger", d.alertRinger),
            alertDnd = sp.getBoolean("alertDnd", d.alertDnd),
            alertUnlock = sp.getBoolean("alertUnlock", d.alertUnlock),
            alertBluetooth = sp.getBoolean("alertBluetooth", d.alertBluetooth),
            statusInIsland = sp.getBoolean("statusInIsland", d.statusInIsland),
            statusOffsetY = sp.getFloat("statusOffsetY", d.statusOffsetY),
            realStatusBar = sp.getBoolean("realStatusBar", d.realStatusBar),
            tabWeather = sp.getBoolean("tabWeather", d.tabWeather),
            tabContacts = sp.getBoolean("tabContacts", d.tabContacts),
            tabCamera = sp.getBoolean("tabCamera", d.tabCamera),
            weatherCity = sp.getString("weatherCity", d.weatherCity) ?: "",
            monochrome = sp.getBoolean("monochrome", d.monochrome),
            cameraPosition = sp.getString("cameraPosition", d.cameraPosition) ?: d.cameraPosition,
            statusStyle = sp.getString("statusStyle", d.statusStyle) ?: d.statusStyle,
            statusShowSignal = sp.getBoolean("statusSignal", d.statusShowSignal),
            statusShowWifi = sp.getBoolean("statusWifi", d.statusShowWifi),
            statusShowBluetooth = sp.getBoolean("statusBt", d.statusShowBluetooth),
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
            .putFloat("appIconSize", c.appIconSize)
            .putInt("appColumns", c.appColumns)
            .putBoolean("appLabels", c.appLabels)
            .putFloat("appBrightness", c.appBrightness)
            .putBoolean("appGrayscale", c.appGrayscale)
            .putBoolean("doorbellTakeover", c.doorbellTakeover)
            .putString("controls", c.controls.joinToString(","))
            .putBoolean("showSettingsTile", c.showSettingsTile)
            .putString("doubleTap", c.doubleTap)
            .putBoolean("liveActivities", c.liveActivities)
            .putBoolean("alertCharging", c.alertCharging)
            .putBoolean("alertLowBattery", c.alertLowBattery)
            .putBoolean("alertRinger", c.alertRinger)
            .putBoolean("alertDnd", c.alertDnd)
            .putBoolean("alertUnlock", c.alertUnlock)
            .putBoolean("alertBluetooth", c.alertBluetooth)
            .putBoolean("statusInIsland", c.statusInIsland)
            .putFloat("statusOffsetY", c.statusOffsetY)
            .putBoolean("realStatusBar", c.realStatusBar)
            .putBoolean("tabWeather", c.tabWeather)
            .putBoolean("tabContacts", c.tabContacts)
            .putBoolean("tabCamera", c.tabCamera)
            .putString("weatherCity", c.weatherCity)
            .putBoolean("monochrome", c.monochrome)
            .putString("cameraPosition", c.cameraPosition)
            .putString("statusStyle", c.statusStyle)
            .putBoolean("statusSignal", c.statusShowSignal)
            .putBoolean("statusWifi", c.statusShowWifi)
            .putBoolean("statusBt", c.statusShowBluetooth)
            .putString("menuApps", c.menuApps.joinToString(","))
            .apply()
    }
}

enum class PreviewMode {
    NONE,
    GLOW,

    /** Hold the island open (on whatever tab fits). */
    EXPANDED,

    /** Hold the island open on the app tab. */
    APPS,
}

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
