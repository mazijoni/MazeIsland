package com.mazedev.island.island

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mazedev.island.IslandConfig
import com.mazedev.island.IslandStyle
import com.mazedev.island.Prefs
import com.mazedev.island.media.MediaRepository
import com.mazedev.island.util.HeadsUp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The accessibility service exists only so we can draw an overlay *over the status bar*,
 * right on the camera cutout. It doesn't read any screen content.
 */
class IslandService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycleOwner = OverlayLifecycleOwner()
    private lateinit var controller: IslandController
    private var wm: WindowManager? = null
    private var root: IslandRootView? = null
    private var popupsHiddenByUs = false
    private val miniWide = MutableStateFlow(false)

    private data class WindowState(
        val cfg: IslandConfig,
        val large: Boolean,
        val focusable: Boolean,
        val miniWide: Boolean,
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        val windowManager = getSystemService(WindowManager::class.java)
        wm = windowManager
        controller = IslandController(scope)
        lifecycleOwner.start()

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { IslandUi(controller) }
        }
        val rootView = IslandRootView(this) { controller.collapse() }.apply {
            addView(
                composeView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        }
        windowManager.addView(rootView, layoutParams(WindowState(Prefs.island.value, false, false, false)))
        root = rootView

        // The collapsed pill is wider while music plays. Grow the window right away, shrink it only
        // after the shrink animation finished.
        scope.launch {
            MediaRepository.media.map { it?.isPlaying == true }.distinctUntilChanged().collectLatest { playing ->
                if (!playing) delay(IslandController.COLLAPSE_ANIM_MS)
                miniWide.value = playing
            }
        }

        // Window size/flags only change at the start of a grow and the end of a shrink, never per frame.
        scope.launch {
            combine(Prefs.island, controller.windowLarge, controller.focusable, miniWide) { cfg, large, focus, wide ->
                WindowState(cfg, large, focus, wide)
            }
                .distinctUntilChanged()
                .collect { applyWindow(it) }
        }
        scope.launch {
            Prefs.island.collect { cfg -> applyPopupSetting(cfg) }
        }
        scope.launch { MediaRepository.notifications.collect { controller.showNotification(it) } }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::controller.isInitialized) {
            applyWindow(
                WindowState(Prefs.island.value, controller.windowLarge.value, controller.focusable.value, miniWide.value)
            )
        }
    }

    private fun applyWindow(state: WindowState) {
        val view = root ?: return
        val portrait = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        view.visibility = if (state.cfg.enabled && portrait) View.VISIBLE else View.GONE
        runCatching { wm?.updateViewLayout(view, layoutParams(state)) }
    }

    /** Turns the system's own pop-up banners off while the island is on (if allowed). */
    private fun applyPopupSetting(cfg: IslandConfig) {
        val hide = cfg.enabled && cfg.hideSystemPopups
        if (hide) {
            if (HeadsUp.setSystemPopups(this, enabled = false)) popupsHiddenByUs = true
        } else if (popupsHiddenByUs) {
            HeadsUp.setSystemPopups(this, enabled = true)
            popupsHiddenByUs = false
        }
    }

    private fun layoutParams(state: WindowState): WindowManager.LayoutParams {
        val cfg = state.cfg
        val large = state.large
        val focusable = state.focusable
        val density = resources.displayMetrics.density
        val pillHeightPx = (cfg.height * density).roundToInt()
        val (centerOffsetX, topY) = cutoutAnchor(pillHeightPx)
        val screenWidthDp = resources.configuration.screenWidthDp

        // Fixed sizes: big enough for every state, so Compose animates inside the window freely.
        val widthPx: Int
        val heightPx: Int
        if (large) {
            widthPx = (expandedWidthDp(screenWidthDp) * density).roundToInt()
            heightPx = ((cfg.height + EXPANDED_CONTENT_DP) * density).roundToInt()
        } else {
            // Exactly the pill's size, so the rest of the status bar stays touchable (shade pull-down).
            val pillWidthDp = when {
                state.miniWide -> cfg.width + MINI_EXTRA_DP
                cfg.style == IslandStyle.HIDDEN -> cfg.height
                else -> cfg.width
            }
            widthPx = (pillWidthDp * density).roundToInt()
            heightPx = pillHeightPx
        }

        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        return WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = centerOffsetX + (cfg.offsetX * density).roundToInt()
            y = topY + (cfg.offsetY * density).roundToInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                if (focusable) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else 0
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /**
     * Returns (x offset from screen center, top y) so the collapsed pill is centered on the
     * front camera hole. Falls back to "centered in the status bar" if there's no cutout info.
     */
    private fun cutoutAnchor(pillHeightPx: Int): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = wm?.currentWindowMetrics
            val rect = metrics?.windowInsets?.displayCutout?.boundingRectTop
            if (metrics != null && rect != null && !rect.isEmpty) {
                val screenW = metrics.bounds.width()
                return (rect.centerX() - screenW / 2) to max(0, rect.centerY() - pillHeightPx / 2)
            }
        }
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (id > 0) resources.getDimensionPixelSize(id) else (24 * resources.displayMetrics.density).roundToInt()
        return 0 to max(0, (statusBar - pillHeightPx) / 2)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (popupsHiddenByUs) HeadsUp.setSystemPopups(this, enabled = true)
        root?.let { runCatching { wm?.removeView(it) } }
        root = null
        lifecycleOwner.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Extra width of the collapsed "now playing" pill (cover on the left, bars on the right). */
        const val MINI_EXTRA_DP = 96f

        /** Room below the camera for the expanded card (pager + dots, or a reply box). */
        const val EXPANDED_CONTENT_DP = 210f

        fun expandedWidthDp(screenWidthDp: Int): Float = min(screenWidthDp - 16, 400).toFloat()
    }
}
