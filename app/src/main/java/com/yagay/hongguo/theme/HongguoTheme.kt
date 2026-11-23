package com.yagay.hongguo

import android.content.Context
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Immutable visual state for all ordinary pages and player sheets.
 * Never writes theme colors through SideEffect or recreates the Media3 player.
 */
@Immutable
internal data class HongguoDesignTokens(
    val accent: Color, val accentSoft: Color, val background: Color,
    val surface: Color, val ink: Color, val muted: Color,
    val divider: Color, val dark: Boolean, val material3: Boolean,
)

@Immutable
internal data class HongguoSpacing(
    val page: Dp = 16.dp, val group: Dp = 12.dp, val item: Dp = 8.dp,
    val minTouchTarget: Dp = 48.dp, val sheetRadius: Dp = 28.dp,
)

internal val LocalHongguoTokens = staticCompositionLocalOf { classicTokens("warm", false) }
internal val LocalHongguoSpacing = staticCompositionLocalOf { HongguoSpacing() }

internal data class HongguoResolvedTheme(
    val colors: ColorScheme,
    val tokens: HongguoDesignTokens,
    val typography: Typography,
    val shapes: Shapes,
    val spacing: HongguoSpacing,
)

internal object HongguoThemePolicy {
    fun isDark(mode: String, systemDark: Boolean): Boolean = when (mode) {
        "dark" -> true
        "system" -> systemDark
        else -> false
    }
    fun shouldUseDynamic(style: String, source: String, sdk: Int): Boolean =
        style == "material3" && source == "dynamic" && sdk >= 31
    /** Width includes system window; subtract page gutters before sizing posters. */
    fun catalogColumns(widthDp: Int, fontScale: Float = 1f): Int {
        val target = if (fontScale >= 1.3f) 184 else 160
        return ((widthDp - 32 + 12).coerceAtLeast(0) / (target + 12)).coerceIn(2, 6)
    }
    fun galleryColumns(widthDp: Int, fontScale: Float = 1f): Int = when {
        widthDp >= 840 -> if (fontScale >= 1.3f) 5 else 6
        widthDp >= 600 -> if (fontScale >= 1.3f) 4 else 5
        widthDp < 360 || fontScale >= 1.3f -> 2
        else -> 3
    }
    fun useTwoPaneDetail(widthDp: Int): Boolean = widthDp >= 840

    fun episodeColumns(widthDp: Int, largeFont: Boolean): Int = when {
        largeFont && widthDp < 600 -> 4
        widthDp < 330 -> 4
        widthDp < 600 -> 5
        else -> (widthDp / 92).coerceIn(5, 8)
    }
}

private fun classicTokens(style: String, dark: Boolean): HongguoDesignTokens {
    val colors = when (style) {
        "immersive" -> if (dark) listOf(
            0xFFD63A24L, 0xFF3A1E19L, 0xFF0E0E11L, 0xFF1A1A1FL,
            0xFFF5F5F7L, 0xFFA3A3ADL, 0xFF2E2E35L,
        ) else listOf(
            0xFFD63A24L, 0xFFFDE9E4L, 0xFFF6F6F8L, 0xFFFFFFFFL,
            0xFF17171BL, 0xFF62626CL, 0xFFDFDFE5L,
        )
        "cards" -> if (dark) listOf(
            0xFFD42E24L, 0xFF3A1C1AL, 0xFF0F1012L, 0xFF1A1B1FL,
            0xFFF2F3F5L, 0xFFA5A9B1L, 0xFF2C2F35L,
        ) else listOf(
            0xFFD42E24L, 0xFFFDE8E6L, 0xFFF3F4F6L, 0xFFFFFFFFL,
            0xFF17181BL, 0xFF5F646CL, 0xFFE1E4E8L,
        )
        else -> if (dark) listOf(
            0xFFC94426L, 0xFF3A241CL, 0xFF1A1512L, 0xFF241E1AL,
            0xFFF5EEE8L, 0xFFB8ACA3L, 0xFF3A322CL,
        ) else listOf(
            0xFFC94426L, 0xFFFFEADFL, 0xFFFCF8F4L, 0xFFFFFFFFL,
            0xFF292421L, 0xFF6B625CL, 0xFFE5DCD5L,
        )
    }
    fun c(i: Int) = Color(colors[i].toInt())
    return HongguoDesignTokens(c(0), c(1), c(2), c(3), c(4), c(5), c(6), dark, false)
}

private fun brandScheme(dark: Boolean): ColorScheme =
    if (dark) darkColorScheme(
        primary = Color(0xFFFFB4A0), onPrimary = Color(0xFF67200C),
        primaryContainer = Color(0xFF8F2D16), onPrimaryContainer = Color(0xFFFFDAD0),
        secondary = Color(0xFFEAB4A3), onSecondary = Color(0xFF46291F),
        secondaryContainer = Color(0xFF624034), onSecondaryContainer = Color(0xFFFFDAD0),
        background = Color(0xFF17120F), onBackground = Color(0xFFEFE0DC),
        surface = Color(0xFF17120F), onSurface = Color(0xFFEFE0DC),
        onSurfaceVariant = Color(0xFFDDC1B9),
        surfaceContainerLowest = Color(0xFF120E0C),
        surfaceContainerLow = Color(0xFF211A18),
        surfaceContainer = Color(0xFF261F1D),
        surfaceContainerHigh = Color(0xFF312A27),
        surfaceContainerHighest = Color(0xFF3C3431),
        outline = Color(0xFFA78B82), outlineVariant = Color(0xFF57423B),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    ) else lightColorScheme(
        primary = Color(0xFFAA341D), onPrimary = Color.White,
        primaryContainer = Color(0xFFFFDAD0), onPrimaryContainer = Color(0xFF3B0901),
        secondary = Color(0xFF78564C), onSecondary = Color.White,
        secondaryContainer = Color(0xFFFFDAD0), onSecondaryContainer = Color(0xFF2D1610),
        background = Color(0xFFFFF8F6), onBackground = Color(0xFF251916),
        surface = Color(0xFFFFF8F6), onSurface = Color(0xFF251916),
        onSurfaceVariant = Color(0xFF59413A),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFFFF1EC),
        surfaceContainer = Color(0xFFFFEBE5),
        surfaceContainerHigh = Color(0xFFFBE5DE),
        surfaceContainerHighest = Color(0xFFF3DDD5),
        outline = Color(0xFF8C7169), outlineVariant = Color(0xFFE1C0B6),
        error = Color(0xFFBA1A1A), onError = Color.White,
        errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    )

private fun classicScheme(t: HongguoDesignTokens): ColorScheme =
    if (t.dark) darkColorScheme(
        primary = t.accent, onPrimary = Color.White,
        background = t.background, onBackground = t.ink,
        surface = t.surface, onSurface = t.ink, onSurfaceVariant = t.muted,
        outlineVariant = t.divider,
    ) else lightColorScheme(
        primary = t.accent, onPrimary = Color.White,
        background = t.background, onBackground = t.ink,
        surface = t.surface, onSurface = t.ink, onSurfaceVariant = t.muted,
        outlineVariant = t.divider,
    )

private val materialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
private val classicShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(7.dp),
    medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(18.dp),
)
private val materialTypography = Typography(
    headlineSmall = Typography().headlineSmall.copy(fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = 0.sp),
    titleLarge = Typography().titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
    titleMedium = Typography().titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    titleSmall = Typography().titleSmall.copy(fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    bodyMedium = Typography().bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodySmall = Typography().bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
    labelLarge = Typography().labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    labelMedium = Typography().labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
)

internal fun resolveHongguoTheme(
    context: Context, uiStyle: String, colorSource: String, style: String,
    mode: String, systemDark: Boolean,
): HongguoResolvedTheme {
    val dark = HongguoThemePolicy.isDark(mode, systemDark)
    val material = uiStyle == "material3"
    val classic = classicTokens(style, dark)
    val scheme = when {
        HongguoThemePolicy.shouldUseDynamic(uiStyle, colorSource, Build.VERSION.SDK_INT) ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        material -> brandScheme(dark)
        else -> classicScheme(classic)
    }
    val tokens = if (material) HongguoDesignTokens(
        scheme.primary, scheme.primaryContainer, scheme.background,
        scheme.surfaceContainerLow, scheme.onSurface, scheme.onSurfaceVariant,
        scheme.outlineVariant, dark, true,
    ) else classic
    return HongguoResolvedTheme(
        colors = scheme, tokens = tokens,
        typography = if (material) materialTypography else Typography(),
        shapes = if (material) materialShapes else classicShapes,
        spacing = if (material) HongguoSpacing() else HongguoSpacing(
            page = 14.dp, group = 10.dp, item = 6.dp, sheetRadius = 14.dp,
        ),
    )
}

@Composable
internal fun HongguoAppTheme(theme: HongguoResolvedTheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalHongguoTokens provides theme.tokens,
        LocalHongguoSpacing provides theme.spacing,
    ) {
        MaterialTheme(
            colorScheme = theme.colors, typography = theme.typography,
            shapes = theme.shapes,
        ) {
            HongguoPosterMotionHost(enabled = theme.tokens.material3, content = content)
        }
    }
}
