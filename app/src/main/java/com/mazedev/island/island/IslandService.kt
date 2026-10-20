package com.mazedev.island.island

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mazedev.island.IslandConfig
import com.mazedev.island.Prefs
import com.mazedev.island.media.MediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.max
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
            addView(composeView)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        }
        windowManager.addView(rootView, layoutParams(Prefs.island.value))
        root = rootView

        scope.launch { Prefs.island.collect { applyConfig(it) } }
        scope.launch { MediaRepository.notifications.collect { controller.showNotification(it) } }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyConfig(Prefs.island.value)
    }

    private fun applyConfig(cfg: IslandConfig) {
        val view = root ?: return
        val portrait = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        view.visibility = if (cfg.enabled && portrait) View.VISIBLE else View.GONE
        runCatching { wm?.updateViewLayout(view, layoutParams(cfg)) }
    }

    private fun layoutParams(cfg: IslandConfig): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        val pillHeightPx = (cfg.height * density).roundToInt()
        val (centerOffsetX, topY) = cutoutAnchor(pillHeightPx)

        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
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
        root?.let { runCatching { wm?.removeView(it) } }
        root = null
        lifecycleOwner.stop()
        scope.cancel()
        super.onDestroy()
    }
}
