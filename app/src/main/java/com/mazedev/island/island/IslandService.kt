package com.mazedev.island.island

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
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
import com.mazedev.island.underStatusBar
import com.mazedev.island.media.MediaRepository
import com.mazedev.island.util.BtDevices
import com.mazedev.island.util.Controls
import com.mazedev.island.util.HeadsUp
import com.mazedev.island.util.Shortcut
import com.mazedev.island.util.ShortcutRunner
import com.mazedev.island.util.sendCompat
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
import kotlin.math.abs
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

        controller.globalAction = { performGlobalAction(it) }
        val firstParams = displayParams(DisplayState(Prefs.island.value, interactive = false, focusable = false, large = false))
        addDisplayWindow(firstParams)

        Controls.prepare(this)
        BtDevices.start(this)
        val tapView = View(this)
        val detector = GestureDetector(this, IslandGestures(tapView)).apply {
            setOnDoubleTapListener(null) // own, faster double-tap handling (see IslandGestures)
        }
        @Suppress("ClickableViewAccessibility")
        tapView.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); true }
        windowManager.addView(tapView, touchParams(Prefs.island.value, true, currentLayout()))
        touchTarget = tapView

        alerts = SystemAlerts(this) { controller.showAlert(it) }.also { it.start() }

        updateLocked()
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            lockReceiver,
            android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_OFF)
                addAction(android.content.Intent.ACTION_SCREEN_ON)
                addAction(android.content.Intent.ACTION_USER_PRESENT)
            },
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )

        val collapsedFlow = controller.mode.map { it == IslandMode.Collapsed }.distinctUntilChanged()
        val previewOpen = IslandPreview.mode.map { it == PreviewMode.EXPANDED || it == PreviewMode.APPS }

        // Display window: only its touch/focus flags change (never its size while animating).
        scope.launch {
            combine(
                combine(Prefs.island, collapsedFlow) { cfg, collapsed -> cfg to collapsed },
                controller.focusable,
                previewOpen,
                combine(controller.windowLarge, IslandPreview.mode) { big, pm -> big || pm != PreviewMode.NONE },
                combine(controller.pinned, controller.pinnedHeightPx, controller.mode) { pin, hpx, m ->
                    // Only while the pinned card itself is showing (not a notification on top).
                    if (pin && hpx > 0 && (m is IslandMode.Expanded || m is IslandMode.Video)) hpx else 0
                },
            ) { (cfg, collapsed), focus, pv, large, pinnedPx ->
                DisplayState(cfg, interactive = !collapsed || pv, focusable = focus, large = large, pinnedHeightPx = pinnedPx)
            }
                .combine(locked) { st, isLocked -> st.copy(locked = isLocked) }
                .distinctUntilChanged()
                .collect { applyDisplay(it) }
        }
        // Touch target: follows the collapsed pill's size; disabled while expanded.
        scope.launch {
            combine(Prefs.island, collapsedFlow, layoutFlow()) { cfg, collapsed, layout ->
                Triple(cfg, collapsed, layout)
            }
                .distinctUntilChanged()
                .collect { (cfg, collapsed, layout) -> applyTouch(cfg, collapsed, layout) }
        }

        // Incoming calls open up on their own (and close when the call is answered or missed).
        scope.launch {
            var shownRinging: String? = null
            MediaRepository.activities.collect { list ->
                val ringing = list.firstOrNull { it.ringing }
                if (ringing != null && ringing.key != shownRinging) {
                    shownRinging = ringing.key
                    controller.showActivity(ringing.key, holdMs = 0)
                } else if (ringing == null && shownRinging != null) {
                    val m = controller.mode.value
                    if (m is IslandMode.Activity && m.key == shownRinging) controller.dismiss()
                    shownRinging = null
                }
                val m = controller.mode.value
                if (m is IslandMode.Activity && list.none { it.key == m.key }) controller.dismiss()
            }
        }
        scope.launch {
            Prefs.island.collect { cfg -> applyPopupSetting(cfg) }
        }
        scope.launch { MediaRepository.notifications.collect { controller.showNotification(it) } }
        // "Share to Maze Island" from YouTube → play it in the island.
        scope.launch { VideoRequests.requests.collect { controller.showVideo(it) } }

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

    private var alerts: SystemAlerts? = null

    private fun currentLayout() = CollapsedLayout.of(
        MediaRepository.media.value?.isPlaying == true,
        MediaRepository.activities.value,
        Controls.torch.value,
    )

    private fun layoutFlow() = combine(
        MediaRepository.media.map { it?.isPlaying == true }.distinctUntilChanged(),
        MediaRepository.activities,
        Controls.torch,
    ) { playing, acts, torch -> CollapsedLayout.of(playing, acts, torch) }.distinctUntilChanged()

    /** Tap, double tap, long press and swipe on the collapsed island. */
    private inner class IslandGestures(private val view: View) : GestureDetector.SimpleOnGestureListener() {
        private val doubleTapEnabled get() = Shortcut.from(Prefs.island.value.doubleTap) != Shortcut.NONE

        override fun onDown(e: MotionEvent) = true

        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var pendingTap: Runnable? = null
        private var lastTapUp = 0L

        /**
         * Without a double-tap shortcut, a tap opens the island right away. With one, a second
         * tap within [DOUBLE_TAP_MS] runs the shortcut, otherwise the first tap opens the island.
         * (Android's default waits ~300 ms and drops taps that wobble; this is shorter and simpler.)
         */
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val now = e.eventTime
            val x = e.x
            if (!doubleTapEnabled) {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                tap(x)
                return true
            }
            val pending = pendingTap
            if (pending != null && now - lastTapUp <= DOUBLE_TAP_MS) {
                handler.removeCallbacks(pending)
                pendingTap = null
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                runShortcut(Shortcut.from(Prefs.island.value.doubleTap))
            } else {
                lastTapUp = now
                val r = Runnable {
                    pendingTap = null
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tap(x)
                }
                pendingTap = r
                handler.postDelayed(r, DOUBLE_TAP_MS)
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val layout = currentLayout()
            when (layout.main) {
                Compact.ACTIVITY -> MediaRepository.activities.value.firstOrNull()?.contentIntent?.sendCompat()
                Compact.MEDIA -> MediaRepository.media.value?.let { MediaRepository.openPlayer(this@IslandService, it.packageName) }
                Compact.TORCH -> controller.showFlashlight()
                Compact.NONE -> controller.expand(startOnApps = true)
            }
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) < abs(vy) * 1.2f || abs(vx) < 400f) return false
            val media = MediaRepository.media.value ?: return false
            if (vx < 0) {
                MediaRepository.next(this@IslandService, media.packageName)
                controller.nudge(-1)
            } else {
                MediaRepository.previous(this@IslandService, media.packageName)
                controller.nudge(1)
            }
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            return true
        }

        private fun tap(x: Float) {
            val layout = currentLayout()
            val cfg = Prefs.island.value
            val density = resources.displayMetrics.density
            val mainHalf = collapsedWidthDp(cfg, layout.main) * density / 2f
            val onBubble = layout.bubble != Compact.NONE && x > view.width / 2f + mainHalf
            when (if (onBubble) layout.bubble else layout.main) {
                Compact.ACTIVITY -> MediaRepository.activities.value.firstOrNull()?.let { controller.showActivity(it.key) }
                Compact.MEDIA -> controller.expand(startOnApps = false)
                Compact.TORCH -> controller.showFlashlight()
                Compact.NONE -> {
                    val anyRecent = MediaRepository.sessions.value.any { it.isRecent() }
                    controller.expand(startOnApps = !anyRecent)
                }
            }
        }
    }

    private fun runShortcut(s: Shortcut) {
        when (s) {
            Shortcut.NONE -> Unit
            Shortcut.FLASHLIGHT -> {
                if (Controls.torchOn) {
                    Controls.setTorch(this, false)
                    controller.collapse()
                } else {
                    Controls.setTorch(this, true)
                    controller.showFlashlight()
                }
            }
            Shortcut.APP_MENU -> controller.expand(startOnApps = true)
            Shortcut.SCREENSHOT -> scope.launch {
                controller.collapse()
                delay(IslandController.COLLAPSE_ANIM_MS)
                ShortcutRunner.run(this@IslandService, s)
            }
            else -> ShortcutRunner.run(this, s)
        }
    }

    /** Lock screen showing. */
    private val locked = MutableStateFlow(false)

    private val lockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, intent: android.content.Intent) {
            updateLocked()
        }
    }

    private fun updateLocked() {
        val km = getSystemService(android.app.KeyguardManager::class.java)
        locked.value = km?.isKeyguardLocked == true
    }

    private data class DisplayState(
        val cfg: IslandConfig,
        val interactive: Boolean,
        val focusable: Boolean,
        /** Only used under the status bar: the window is cut to the pill's height while collapsed. */
        val large: Boolean = true,
        /**
         * Pinned: the window is cut to the card's height, so the rest of the screen stays usable
         * (only the height changes, so the island doesn't move).
         */
        val pinnedHeightPx: Int = 0,
        val locked: Boolean = false,
    )

    private var displayType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::controller.isInitialized) return
        val cfg = Prefs.island.value
        val collapsed = controller.mode.value == IslandMode.Collapsed
        applyDisplay(
            DisplayState(
                cfg, !collapsed, controller.focusable.value,
                controller.windowLarge.value || IslandPreview.mode.value != PreviewMode.NONE,
            )
        )
        applyTouch(cfg, collapsed, currentLayout())
    }

    private fun visible(cfg: IslandConfig): Boolean =
        cfg.enabled && resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

    private fun newDisplayView(): IslandRootView {
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { IslandUi(controller) }
        }
        return IslandRootView(this) { controller.dismiss() }.apply {
            addView(
                composeView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        }
    }

    /**
     * Adds the drawing window with a fresh view. If drawing under the status bar isn't allowed,
     * falls back to the normal (above) layer and reports why in Settings.
     */
    private fun addDisplayWindow(params: WindowManager.LayoutParams) {
        val windowManager = wm ?: return
        val view = newDisplayView()
        val result = runCatching { windowManager.addView(view, params) }
        if (result.isSuccess) {
            display = view
            displayType = params.type
            LayerStatus.set(
                if (params.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) LayerStatus.UNDER else LayerStatus.ABOVE
            )
        } else {
            val fallback = WindowManager.LayoutParams().apply { copyFrom(params) }
            fallback.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            val view2 = newDisplayView()
            runCatching { windowManager.addView(view2, fallback) }
            display = view2
            displayType = fallback.type
            LayerStatus.set("Couldn't draw under the status bar: ${result.exceptionOrNull()?.message ?: "unknown error"}")
        }
    }

    private fun applyDisplay(state: DisplayState) {
        val view = display ?: return
        val params = displayParams(state)
        if (params.type != displayType) {
            // A window's layer can't change in place: replace the window (switching real status bar on/off).
            runCatching { wm?.removeViewImmediate(view) }
            display = null
            addDisplayWindow(params)
            display?.visibility = if (visible(state.cfg)) View.VISIBLE else View.GONE
        } else {
            view.visibility = if (visible(state.cfg)) View.VISIBLE else View.GONE
            runCatching { wm?.updateViewLayout(view, params) }
        }
    }

    private fun applyTouch(cfg: IslandConfig, collapsed: Boolean, layout: CollapsedLayout) {
        val view = touchTarget ?: return
        view.visibility = if (visible(cfg)) View.VISIBLE else View.GONE
        runCatching { wm?.updateViewLayout(view, touchParams(cfg, collapsed, layout)) }
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

    private fun baseParams(
        widthPx: Int,
        heightPx: Int,
        cfg: IslandConfig,
        flags: Int,
        type: Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    ): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        val (centerOffsetX, topY) = cutoutAnchor((cfg.height * density).roundToInt())
        return WindowManager.LayoutParams(
            widthPx,
            heightPx,
            type,
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

    private fun displayParams(state: DisplayState): WindowManager.LayoutParams {
        val cfg = state.cfg
        val interactive = state.interactive
        val focusable = state.focusable
        val density = resources.displayMetrics.density
        val widthPx = resources.displayMetrics.widthPixels
        // Under the status bar we're an ordinary app overlay. Android blocks touches that pass
        // through such a window to other apps, so while collapsed it's cut to the pill's height
        // (which sits inside the status bar, where touches go to the status bar anyway).
        // Only the height changes, never the width, so the island can't jump sideways.
        // Android never shows "display over other apps" windows on the lock screen, so while
        // locked the island uses its normal (accessibility) layer instead.
        val under = cfg.underStatusBar(this) && !locked.value
        val heightPx = if (state.pinnedHeightPx > 0) {
            state.pinnedHeightPx + (2 * density).roundToInt()
        } else if (under && !state.large) {
            (cfg.height * density).roundToInt()
        } else {
            ((cfg.height + EXPANDED_CONTENT_DP) * density).roundToInt()
        }
        val type = if (under && !locked.value) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        // Android caps "display over other apps" windows that can't be touched at 80% opacity
        // (anti-tapjacking), which turned the black island gray. Under the status bar the window
        // stays touchable instead: while collapsed it's only pill-high and sits inside the status
        // bar, which is on top and takes those touches anyway.
        if (!interactive && !under) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return baseParams(widthPx, heightPx, cfg, flags, type).apply {
            alpha = 1f
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                if (focusable) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE else 0
        }
    }

    private fun touchParams(cfg: IslandConfig, collapsed: Boolean, layout: CollapsedLayout): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        // The pill (+ the bubble on both sides, to stay centered) plus a little extra all round so
        // near-misses still count, while most of the status bar stays free for pulling down the shade.
        val bubbleExtra = if (layout.bubble != Compact.NONE) 2 * (BUBBLE_GAP_DP + cfg.height) else 0f
        val pillWidthDp = maxOf(collapsedWidthDp(cfg, layout.main) + bubbleExtra + 2 * TOUCH_SLOP_DP, 80f)
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!collapsed) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return baseParams(
            (pillWidthDp * density).roundToInt(),
            ((cfg.height + TOUCH_SLOP_DP) * density).roundToInt(),
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
        alerts?.stop()
        runCatching { unregisterReceiver(lockReceiver) }
        display?.let { runCatching { wm?.removeView(it) } }
        touchTarget?.let { runCatching { wm?.removeView(it) } }
        display = null
        touchTarget = null
        lifecycleOwner.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Extra touch area around the collapsed pill (dp). */
        const val TOUCH_SLOP_DP = 12f

        /** Max gap between the two taps of a double tap. */
        const val DOUBLE_TAP_MS = 230L

        /** Extra width of the collapsed "now playing" pill (cover on the left, bars on the right). */
        const val MINI_EXTRA_DP = 96f

        /** Room below the camera for the expanded card (pager + dots, or a reply box). */
        const val EXPANDED_CONTENT_DP = 440f

        fun expandedWidthDp(screenWidthDp: Int, marginDp: Float): Float =
            min(screenWidthDp - 2f * marginDp, 480f)
    }
}
