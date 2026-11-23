package com.yagay.hongguo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Player viewport colors are intentionally independent of the light/dynamic
 * page scheme. Sheet/dialog colors still come from LocalHongguoTokens so
 * their text retains adequate contrast in both Classic and Material 3.
 */
internal object HongguoPlayerPalette {
    val viewportBackground = Color(0xFF101012)
}

/** Read-only compatibility bridge to HongguoAppTheme's CompositionLocal. */
internal object HongguoColors {
    val accent: Color @Composable @ReadOnlyComposable get() = LocalHongguoTokens.current.accent
    val accentSoft: Color @Composable @ReadOnlyComposable get() = LocalHongguoTokens.current.accentSoft
    val background: Color @Composable @ReadOnlyComposable get() = LocalHongguoTokens.current.background
    val surface: Color @Composable @ReadOnlyComposable get() = LocalHongguoTokens.current.surface
    val ink: Color @Composable @ReadOnlyComposable get() = LocalHongguoTokens.current.ink
    val muted: Color @Composable @ReadOnlyComposable get() = LocalHongguoTokens.current.muted
    val divider: Color @Composable @ReadOnlyComposable get() = LocalHongguoTokens.current.divider
    val isDark: Boolean @Composable @ReadOnlyComposable get() = LocalHongguoTokens.current.dark
    val isMaterial3: Boolean @Composable @ReadOnlyComposable get() = LocalHongguoTokens.current.material3
    val playerBackground = HongguoPlayerPalette.viewportBackground
    val playerSheet: Color @Composable @ReadOnlyComposable get() = surface
    val playerChrome: Color @Composable @ReadOnlyComposable get() = surface
    val playerChromeMuted: Color @Composable @ReadOnlyComposable get() = muted
    val playerChromeTrack: Color @Composable @ReadOnlyComposable get() = muted.copy(alpha = 0.45f)
    val playerControlBackground: Color @Composable @ReadOnlyComposable get() = background
}
