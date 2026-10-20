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
import com.mazedev.island.IslandPreview
import com.mazedev.island.PreviewMode
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

    /**
     * Two windows:
     *  - [display]: draws the island. Always the same full-width size and position, so the
     *    island can never jump when it grows or shrinks (resizing a window shows a stale frame
     *    in the wrong place for a moment, which is what caused the side-jumps).
     *    It lets touches pass through while collapsed.
     *  - [touchTarget]: an invisible, pill-sized window that catches taps on the collapsed island.
     */
    private var display: IslandRootView? = null
    private var touchTarget: View? = null
    private var popupsHiddenByUs = false

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
        val displayView = IslandRootView(this) { controller.collapse() }.apply {
            addView(
                composeView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        }
        windowManager.addView(displayView, displayParams(Prefs.island.value, interactive = false, focusable = false))
        display = displayView

        val tapView = View(this).apply {
            setOnClickListener {
                val anyRecent = MediaRepository.sessions.value.any { it.isRecent() }
                controller.expand(startOnApps = !anyRecent)
            }
        }
        windowManager.addView(tapView, touchParams(Prefs.island.value, collapsed = true, wide = false))
        touchTarget = tapView

        val collapsedFlow = controller.mode.map { it == IslandMode.Collapsed }.distinctUntilChanged()
        val previewOpen = IslandPreview.mode.map { it == PreviewMode.EXPANDED || it == PreviewMode.APPS }

        // Display window: only its touch/focus flags change (never its size while animating).
        scope.launch {
            combine(Prefs.island, collapsedFlow, controller.focusable, previewOpen) { cfg, collapsed, focus, pv ->
                DisplayState(cfg, interactive = !collapsed || pv, focusable = focus)
            }
                .distinctUntilChanged()
                .collect { applyDisplay(it) }
        }
        // Touch target: follows the collapsed pill's size; disabled while expanded.
        scope.launch {
            combine(
                Prefs.island,
                collapsedFlow,
                MediaRepository.media.map { it?.isPlaying == true }.distinctUntilChanged(),
            ) { cfg, collapsed, playing -> Triple(cfg, collapsed, playing) }
                .distinctUntilChanged()
                .collect { (cfg, collapsed, playing) -> applyTouch(cfg, collapsed, playing) }
        }
        scope.launch {
            Prefs.island.collect { cfg -> applyPopupSetting(cfg) }
        }
        scope.launch { MediaRepository.notifications.collect { controller.showNotification(it) } }

        // Settings preview: hold the island open while the expanded-card sliders move.
        scope.launch {
            var openedByPreview = false
            IslandPreview.mode.collect { m ->
                if (m == PreviewMode.EXPANDED || m == PreviewMode.APPS) {
                    val apps = m == PreviewMode.APPS || MediaRepository.sessions.value.none { it.isRecent() }
                    controller.holdExpanded(startOnApps = apps)
                    openedByPreview = true
                } else if (openedByPreview) {
                    openedByPreview = false
                    controller.collapse()
                }
            }
        }
    }

    private data class DisplayState(val cfg: IslandConfig, val interactive: Boolean, val focusable: Boolean)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::controller.isInitialized) return
        val cfg = Prefs.island.value
        val collapsed = controller.mode.value == IslandMode.Collapsed
        applyDisplay(DisplayState(cfg, !collapsed, controller.focusable.value))
        applyTouch(cfg, collapsed, MediaRepository.media.value?.isPlaying == true)
    }

    private fun visible(cfg: IslandConfig): Boolean =
        cfg.enabled && resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    private fun applyDisplay(state: DisplayState) {
        val view = display ?: return
        view.visibility = if (visible(state.cfg)) View.VISIBLE else View.GONE
        runCatching { wm?.updateViewLayout(view, displayParams(state.cfg, state.interactive, state.focusable)) }
    }

    private fun applyTouch(cfg: IslandConfig, collapsed: Boolean, playing: Boolean) {
        val view = touchTarget ?: return
        view.visibility = if (visible(cfg)) View.VISIBLE else View.GONE
        runCatching { wm?.updateViewLayout(view, touchParams(cfg, collapsed, playing)) }
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

    private fun baseParams(widthPx: Int, heightPx: Int, cfg: IslandConfig, flags: Int): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        val (centerOffsetX, topY) = cutoutAnchor((cfg.height * density).roundToInt())
        return WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = centerOffsetX + (cfg.offsetX * density).roundToInt()
            y = topY + (cfg.offsetY * density).roundToInt()
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun displayParams(cfg: IslandConfig, interactive: Boolean, focusable: Boolean): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        val widthPx = resources.displayMetrics.widthPixels
        val heightPx = ((cfg.height + EXPANDED_CONTENT_DP) * density).roundToInt()
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        if (!interactive) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return baseParams(widthPx, heightPx, cfg, flags).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                if (focusable) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else 0
        }
    }

    private fun touchParams(cfg: IslandConfig, collapsed: Boolean, wide: Boolean): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        // Exactly the pill's size, so the rest of the status bar stays touchable (shade pull-down).
        val pillWidthDp = when {
            wide -> cfg.width + MINI_EXTRA_DP
            cfg.style == IslandStyle.HIDDEN -> cfg.height
            else -> cfg.width
        }
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!collapsed) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return baseParams(
            (pillWidthDp * density).roundToInt(),
            (cfg.height * density).roundToInt(),
            cfg,
            flags,
        )
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
        display?.let { runCatching { wm?.removeView(it) } }
        touchTarget?.let { runCatching { wm?.removeView(it) } }
        display = null
        touchTarget = null
        lifecycleOwner.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Extra width of the collapsed "now playing" pill (cover on the left, bars on the right). */
        const val MINI_EXTRA_DP = 96f

        /** Room below the camera for the expanded card (pager + dots, or a reply box). */
        const val EXPANDED_CONTENT_DP = 440f

        fun expandedWidthDp(screenWidthDp: Int, marginDp: Float): Float =
            min(screenWidthDp - 2f * marginDp, 480f)
    }
}
