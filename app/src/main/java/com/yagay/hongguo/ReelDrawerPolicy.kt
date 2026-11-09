package com.yagay.hongguo

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

/** Keep dismissal working even if the user returned in a non-feed series mode. */
internal object ReelDrawerPolicy {
    fun gesturesEnabled(isOpen: Boolean, feedMode: Boolean, searchOverlayOpen: Boolean): Boolean =
        isOpen || (feedMode && !searchOverlayOpen)
}

/**
 * Drawer visibility is transient UI, not saved navigation state.
 * Do not restore an open drawer after Android system PiP or process recreation.
 * Closing on pause/stop/resume also handles PiP expansion on OEMs that keep
 * the player composable mounted through the entire window transition.
 */
@Composable
internal fun rememberTransientReelDrawerState(): DrawerState {
    val state = remember { DrawerState(DrawerValue.Closed) }
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(owner, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE ||
                event == Lifecycle.Event.ON_STOP ||
                event == Lifecycle.Event.ON_RESUME) {
                scope.launch { state.snapTo(DrawerValue.Closed) }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}
