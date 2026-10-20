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
        if (_mode.value is IslandMode.Expanded) autoCollapse(10_000)
    }

    fun showNotification(event: NotifEvent) {
        // Don't interrupt someone typing a reply.
        val current = _mode.value
        if (current is IslandMode.Notification && current.replying) return
        set(IslandMode.Notification(event))
        autoCollapse(if (event.replyAction != null) 7_000 else 5_000)
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
