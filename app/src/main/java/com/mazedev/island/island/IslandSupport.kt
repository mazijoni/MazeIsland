package com.mazedev.island.island

import android.content.Context
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
    data object Expanded : IslandMode
    data class Notification(val event: NotifEvent) : IslandMode
}

class IslandController(private val scope: CoroutineScope) {
    private val _mode = MutableStateFlow<IslandMode>(IslandMode.Collapsed)
    val mode: StateFlow<IslandMode> = _mode.asStateFlow()
    private var timer: Job? = null

    fun expand() {
        _mode.value = IslandMode.Expanded
        autoCollapse(8_000)
    }

    fun collapse() {
        timer?.cancel()
        _mode.value = IslandMode.Collapsed
    }

    /** Call on any interaction so the expanded island doesn't close under your finger. */
    fun poke() {
        if (_mode.value == IslandMode.Expanded) autoCollapse(8_000)
    }

    fun showNotification(event: NotifEvent) {
        _mode.value = IslandMode.Notification(event)
        autoCollapse(5_000)
    }

    private fun autoCollapse(ms: Long) {
        timer?.cancel()
        timer = scope.launch {
            delay(ms)
            _mode.value = IslandMode.Collapsed
        }
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
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutsideTouch()
            return false
        }
        return super.dispatchTouchEvent(ev)
    }
}
