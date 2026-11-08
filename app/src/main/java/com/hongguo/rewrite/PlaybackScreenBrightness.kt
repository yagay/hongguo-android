package com.hongguo.rewrite

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Only a visible full-screen playback window may override display brightness.
 * Never change Settings.System.SCREEN_BRIGHTNESS or force an HDR/SDR pipeline.
 * Disposing the effect restores the window's previous value (-1f usually means
 * "follow system"), including when the player is minimized or enters PiP.
 */
@Composable
internal fun PlaybackScreenBrightness(
    activity: Activity?,
    enabled: Boolean,
    percent: Int,
    onDiagnostic: (String) -> Unit = {},
) {
    DisposableEffect(activity, enabled, percent) {
        val window = activity?.window
        val override = window != null && enabled &&
            VideoBrightnessPolicy.isOverride(percent)
        val previous = if (override) window?.attributes?.screenBrightness else null
        if (enabled && !override) onDiagnostic("SYSTEM")
        if (override) {
            onDiagnostic("OVERRIDE_${percent}PCT")
            val attributes = window!!.attributes
            attributes.screenBrightness = VideoBrightnessPolicy.toWindowValue(percent)
            window.attributes = attributes
        }
        onDispose {
            if (override && previous != null) {
                val attributes = window!!.attributes
                attributes.screenBrightness = previous
                window.attributes = attributes
                onDiagnostic("RESTORED")
            }
        }
    }
}

internal object VideoBrightnessPolicy {
    fun isOverride(value: Int) = value in 40..100
    fun toWindowValue(value: Int): Float =
        if (isOverride(value)) value / 100f
        else android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
}
