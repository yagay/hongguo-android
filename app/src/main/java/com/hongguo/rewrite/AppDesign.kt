package com.hongguo.rewrite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Observable, shared design tokens. All existing screens automatically follow theme changes. */
internal object HongguoColors {
    var accent by mutableStateOf(Color(0xFFE95336)); private set
    var accentSoft by mutableStateOf(Color(0xFFFFEEE9)); private set
    var background by mutableStateOf(Color(0xFFF8F8F7)); private set
    var surface by mutableStateOf(Color.White); private set
    var ink by mutableStateOf(Color(0xFF232326)); private set
    var muted by mutableStateOf(Color(0xFF85858B)); private set
    var divider by mutableStateOf(Color(0xFFF0F0F1)); private set
    var isDark by mutableStateOf(false); private set
    val playerBackground = Color(0xFF101012)
    // Player controls use the same palette as Library/Settings. Only the
    // video viewport is deliberately immersive and permanently dark.
    val playerSheet: Color get() = surface
    val playerChrome: Color get() = surface
    val playerChromeMuted: Color get() = muted
    val playerChromeTrack: Color get() = muted.copy(alpha = 0.45f)
    val playerControlBackground: Color get() = background

    /** References the shared color design of main's warm / immersive / cards variants.
     * Apply only after composition through SideEffect to keep snapshot writes safe.
     */
    fun applyTheme(style: String, mode: String, systemDark: Boolean) {
        val dark = when (mode) {
            "dark" -> true
            "system" -> systemDark
            else -> false
        }
        val colors = when (style) {
            "immersive" -> if (dark) listOf(
                0xFFD63A24L, 0xFF3A1E19L, 0xFF0E0E11L, 0xFF1A1A1FL, 0xFFF5F5F7L,
                0xFFA3A3ADL, 0xFF2E2E35L
            ) else listOf(
                0xFFD63A24L, 0xFFFDE9E4L, 0xFFF6F6F8L, 0xFFFFFFFFL, 0xFF17171BL,
                0xFF62626CL, 0xFFDFDFE5L
            )
            "cards" -> if (dark) listOf(
                0xFFD42E24L, 0xFF3A1C1AL, 0xFF0F1012L, 0xFF1A1B1FL, 0xFFF2F3F5L,
                0xFFA5A9B1L, 0xFF2C2F35L
            ) else listOf(
                0xFFD42E24L, 0xFFFDE8E6L, 0xFFF3F4F6L, 0xFFFFFFFFL, 0xFF17181BL,
                0xFF5F646CL, 0xFFE1E4E8L
            )
            else -> if (dark) listOf(
                0xFFC94426L, 0xFF3A241CL, 0xFF1A1512L, 0xFF241E1AL, 0xFFF5EEE8L,
                0xFFB8ACA3L, 0xFF3A322CL
            ) else listOf(
                0xFFC94426L, 0xFFFFEADFL, 0xFFFCF8F4L, 0xFFFFFFFFL, 0xFF292421L,
                0xFF6B625CL, 0xFFE5DCD5L
            )
        }
        fun c(index: Int) = Color(colors[index].toInt()) // ARGB 0xAARRGGBB; never packed Color(ULong)
        if (accent != c(0)) accent = c(0)
        if (accentSoft != c(1)) accentSoft = c(1)
        if (background != c(2)) background = c(2)
        if (surface != c(3)) surface = c(3)
        if (ink != c(4)) ink = c(4)
        if (muted != c(5)) muted = c(5)
        if (divider != c(6)) divider = c(6)
        if (isDark != dark) isDark = dark
    }
}
