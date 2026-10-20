package com.mazedev.island.island

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.mazedev.island.media.NotifEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the island is doing right now. */
sealed interface IslandMode {
    data object Collapsed : IslandMode

    /** [startOnApps] = open on the app menu tab instead of the media tab. */
    data class Expanded(val startOnApps: Boolean) : IslandMode

    data class Notification(val event: NotifEvent, val replying: Boolean = false, val sent: Boolean = false) : IslandMode

    /** iOS-style flashlight card with a brightness bar. */
    data object Flashlight : IslandMode

    /** Short system alert (charging, silent…). */
    data class Alert(val alert: SystemAlert) : IslandMode

    /** A live activity opened up (timer, call…). */
    data class Activity(val key: String) : IslandMode
}

class IslandController(private val scope: CoroutineScope) {
    private val _mode = MutableStateFlow<IslandMode>(IslandMode.Collapsed)
    val mode: StateFlow<IslandMode> = _mode.asStateFlow()

    /**
     * Whether the overlay window is at its big size. It grows *before* the expand animation and
     * shrinks only *after* the collapse animation, so the window is never resized mid-animation
     * (resizing a window every frame is what made the first version stutter).
     */
    private val _windowLarge = MutableStateFlow(false)
    val windowLarge: StateFlow<Boolean> = _windowLarge.asStateFlow()

    /** True while typing a reply: the window must be focusable to get the keyboard. */
    private val _focusable = MutableStateFlow(false)
    val focusable: StateFlow<Boolean> = _focusable.asStateFlow()

    /** -1 / +1 while the collapsed pill does a little "nudge" after a swipe. */
    private val _nudge = MutableStateFlow(0)
    val nudge: StateFlow<Int> = _nudge.asStateFlow()

    private var timer: Job? = null
    private var shrinkJob: Job? = null

    private fun set(mode: IslandMode) {
        shrinkJob?.cancel()
        if (mode != IslandMode.Collapsed) _windowLarge.value = true
        _focusable.value = mode is IslandMode.Notification && mode.replying
        _mode.value = mode
        if (mode == IslandMode.Collapsed) {
            shrinkJob = scope.launch {
                delay(COLLAPSE_ANIM_MS)
                _windowLarge.value = false
            }
        }
    }

    fun expand(startOnApps: Boolean) {
        set(IslandMode.Expanded(startOnApps))
        autoCollapse(10_000)
    }

    /** Opens without the auto-close timer (used by the Settings preview). */
    fun holdExpanded(startOnApps: Boolean) {
        timer?.cancel()
        val current = _mode.value
        if (current !is IslandMode.Expanded || current.startOnApps != startOnApps) {
            set(IslandMode.Expanded(startOnApps))
        }
    }

    fun collapse() {
        timer?.cancel()
        set(IslandMode.Collapsed)
    }

    /** Call on any interaction so the expanded island doesn't close under your finger. */
    fun poke() {
        when (_mode.value) {
            is IslandMode.Expanded -> autoCollapse(10_000)
            IslandMode.Flashlight, is IslandMode.Activity -> autoCollapse(6_000)
            else -> Unit
        }
    }

    fun showNotification(event: NotifEvent) {
        // Don't interrupt someone typing a reply.
        val current = _mode.value
        if (current is IslandMode.Notification && current.replying) return
        set(IslandMode.Notification(event))
        autoCollapse(if (event.replyAction != null) 7_000 else 5_000)
    }

    fun showFlashlight() {
        set(IslandMode.Flashlight)
        autoCollapse(6_000)
    }

    fun showActivity(key: String, holdMs: Long = 8_000) {
        set(IslandMode.Activity(key))
        if (holdMs > 0) autoCollapse(holdMs) else timer?.cancel()
    }

    /** Alerts only show while nothing else is open. */
    fun showAlert(alert: SystemAlert) {
        val current = _mode.value
        if (current != IslandMode.Collapsed && current !is IslandMode.Alert) return
        set(IslandMode.Alert(alert))
        autoCollapse(2_400)
    }

    fun nudge(direction: Int) {
        _nudge.value = direction
        scope.launch {
            delay(140)
            _nudge.value = 0
        }
    }

    /** Keep a card open while it's being used (sliders etc.). */
    fun keepOpen(ms: Long = 6_000) {
        if (_mode.value != IslandMode.Collapsed) autoCollapse(ms)
    }

    fun startReply() {
        val current = _mode.value as? IslandMode.Notification ?: return
        timer?.cancel()
        set(current.copy(replying = true))
    }

    fun replySent() {
        val current = _mode.value as? IslandMode.Notification ?: return
        set(current.copy(replying = false, sent = true))
        autoCollapse(1_200)
    }

    private fun autoCollapse(ms: Long) {
        timer?.cancel()
        timer = scope.launch {
            delay(ms)
            set(IslandMode.Collapsed)
        }
    }

    companion object {
        const val COLLAPSE_ANIM_MS = 700L
    }
}

/** Compose needs a lifecycle + saved state owner; a service doesn't have one, so we fake a "resumed" one. */
class OverlayLifecycleOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private var started = false

    fun start() {
        if (started) return
        started = true
        savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
        if (!started) return
        started = false
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}

/** Root view of the overlay window. Taps outside the island collapse it. */
class IslandRootView(context: Context, private val onOutsideTouch: () -> Unit) : FrameLayout(context) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onOutsideTouch()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutsideTouch()
            return false
        }
        return super.dispatchTouchEvent(ev)
    }
}

/** What the collapsed island shows. */
enum class Compact { NONE, MEDIA, ACTIVITY, TORCH }

/**
 * The collapsed island's content. Like iOS, with two things going on, the most important one
 * stays in the pill and the second one detaches into a small round bubble next to it.
 */
data class CollapsedLayout(val main: Compact, val bubble: Compact) {
    companion object {
        fun of(
            mediaPlaying: Boolean,
            activities: List<com.mazedev.island.media.LiveActivity>,
            torch: Boolean,
        ): CollapsedLayout {
            val main = when {
                activities.isNotEmpty() -> Compact.ACTIVITY
                mediaPlaying -> Compact.MEDIA
                torch -> Compact.TORCH
                else -> Compact.NONE
            }
            val bubble = when {
                main == Compact.ACTIVITY && mediaPlaying -> Compact.MEDIA
                main != Compact.TORCH && main != Compact.NONE && torch -> Compact.TORCH
                else -> Compact.NONE
            }
            return CollapsedLayout(main, bubble)
        }
    }
}

/** Width of the collapsed main pill in dp. */
fun collapsedWidthDp(cfg: com.mazedev.island.IslandConfig, main: Compact): Float = when (main) {
    Compact.NONE -> if (cfg.style == com.mazedev.island.IslandStyle.HIDDEN) cfg.height else cfg.width
    Compact.MEDIA, Compact.ACTIVITY -> cfg.width + IslandService.MINI_EXTRA_DP
    Compact.TORCH -> cfg.width + 56f
}

const val BUBBLE_GAP_DP = 6f
