package com.yagay.hongguo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Theaters
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared visual rules. Player uses the same sizes/radii with dark surfaces.
 * Keep screen-specific data and actions in their own composables.
 */
internal object HongguoUi {
    val cardRadius = 16.dp
    val controlRadius = 10.dp
    val smallRadius = 7.dp
    val iconButtonSize = 40.dp
    val leadingIconBox = 36.dp
    val leadingIconSize = 20.dp
    val rowTitleSize = 16.sp
    val rowDescriptionSize = 12.sp
    val chipTextSize = 12.sp
    val chipGap = 8.dp
    val pagePadding = 16.dp
    val pageTopBarHeight = 56.dp
    val pageTitleSize = 22.sp
    val sectionTitleSize = 18.sp
    val pageSectionGap = 12.dp
    val primaryNavHeight = 40.dp
    val primaryNavIconSize = 27.dp
    val playbackSeekTouchHeight = 12.dp
    /** Keeps 0% and 100% outside the OS back-gesture edge zone. */
    val playbackSeekHorizontalInset = 24.dp
    val playbackDockRowHeight = 34.dp
    val playbackDockTimeSize = 12.sp
    val playbackDockTitleSize = 12.sp
}

/**
 * Shared coordinate mapping for the progress bar and its wider gesture row.
 * Both use the same 24dp internal inset so the visual endpoint and the seek
 * gesture always agree. Input still covers the entire dock width.
 */
internal object PlaybackSeekGeometry {
    fun safeInsetPx(widthPx: Float, insetPx: Float): Float =
        if (!widthPx.isFinite() || widthPx <= 0f || !insetPx.isFinite()) 0f
        else insetPx.coerceIn(0f, widthPx * 0.4f)

    fun fractionAt(xPx: Float, widthPx: Float, insetPx: Float = 0f): Float {
        if (!xPx.isFinite() || !widthPx.isFinite() || widthPx <= 0f) return 0f
        val inset = safeInsetPx(widthPx, insetPx)
        return ((xPx - inset) / (widthPx - inset * 2f)).coerceIn(0f, 1f)
    }

    fun positionAt(durationMs: Long, fraction: Float): Long =
        if (durationMs <= 0L || !fraction.isFinite()) 0L
        else (durationMs.toDouble() * fraction.coerceIn(0f, 1f).toDouble())
            .toLong().coerceIn(0L, durationMs)
}

