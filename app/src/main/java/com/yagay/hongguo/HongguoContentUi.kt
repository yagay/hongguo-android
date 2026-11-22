package com.yagay.hongguo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** One poster surface shared by catalog, account, history and related works.
 * Page-specific aspect ratio, click handling and overlays stay with the caller.
 */
@Composable
internal fun HongguoPosterArtwork(
    cover: Any?,
    title: String,
    modifier: Modifier = Modifier,
    classicRadius: Dp = HongguoUi.controlRadius,
    classicPlaceholderColor: Color = HongguoColors.accentSoft,
    fallback: @Composable BoxScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val radius = if (HongguoColors.isMaterial3) 16.dp else classicRadius
    Box(modifier.clip(RoundedCornerShape(radius))
        .background(if (HongguoColors.isMaterial3) HongguoColors.accentSoft
            else classicPlaceholderColor)) {
        if (cover != null && (cover !is String || cover.isNotBlank())) {
            AsyncImage(model = cover, contentDescription = title,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else fallback()
        overlay()
    }
}

/** Single input contract; pages keep their own query and submit callbacks. */
@Composable
internal fun HongguoSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索短剧、演员或关键词",
    label: String? = null,
    compact: Boolean = false,
    onSearch: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = modifier,
        placeholder = if (label == null) ({ Text(placeholder,
            fontSize = if (compact) 13.sp else 14.sp,
            color = HongguoColors.muted, maxLines = 1) }) else null,
        label = if (label != null) ({ Text(label) }) else null,
        leadingIcon = if (compact) ({
            Icon(Icons.Outlined.Search, contentDescription = null,
                tint = HongguoColors.muted, modifier = Modifier.size(19.dp))
        }) else null,
        singleLine = true,
        shape = when {
            HongguoColors.isMaterial3 -> RoundedCornerShape(28.dp)
            compact -> RoundedCornerShape(HongguoUi.controlRadius)
            else -> OutlinedTextFieldDefaults.shape
        },
        colors = if (compact) OutlinedTextFieldDefaults.colors(
            focusedTextColor = HongguoColors.ink,
            unfocusedTextColor = HongguoColors.ink,
            cursorColor = HongguoColors.ink,
            focusedContainerColor = HongguoColors.surface,
            unfocusedContainerColor = HongguoColors.surface,
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
        ) else OutlinedTextFieldDefaults.colors(),
        keyboardOptions = KeyboardOptions(
            imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Default),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
    )
}

/** The theater opens the same search page instead of owning another text field. */
@Composable
internal fun HongguoSearchEntry(
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(if (HongguoColors.isMaterial3) 28.dp
            else HongguoUi.controlRadius),
        color = HongguoColors.background) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(Icons.Outlined.Search, contentDescription = null,
                tint = HongguoColors.muted, modifier = Modifier.size(21.dp))
            Text(placeholder, modifier = Modifier.weight(1f),
                color = HongguoColors.muted, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun HongguoMetadataBadge(text: String, modifier: Modifier = Modifier) {
    Text(text, color = HongguoColors.muted,
        fontSize = if (HongguoColors.isMaterial3) 11.sp else 10.sp,
        maxLines = 1,
        modifier = modifier.background(HongguoColors.surface,
            RoundedCornerShape(if (HongguoColors.isMaterial3) 12.dp else 4.dp))
            .padding(horizontal = 5.dp, vertical = 3.dp))
}

@Composable
internal fun HongguoInlineLoading(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier = modifier.size(26.dp),
        color = HongguoColors.accent)
}

@Composable
internal fun HongguoInlineError(message: String, modifier: Modifier = Modifier) {
    Text(message, modifier = modifier, fontSize = 13.sp,
        color = if (HongguoColors.isMaterial3) MaterialTheme.colorScheme.error
            else HongguoColors.muted)
}

/** Editable field content stays screen-owned; form chrome is shared. */
@Composable
internal fun HongguoFormDialog(
    title: String,
    confirmLabel: String,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = HongguoColors.ink) },
        text = content,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirmLabel, color = if (confirmEnabled)
                    HongguoColors.accent else HongguoColors.muted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = HongguoColors.ink) }
        },
        shape = RoundedCornerShape(if (HongguoColors.isMaterial3) 28.dp
            else HongguoUi.cardRadius),
        containerColor = HongguoColors.surface,
    )
}

/** Dense, equal-width option shared by Theater and future compact filter grids. */
@Composable
internal fun HongguoFilterOptionCell(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val material3 = HongguoColors.isMaterial3
    Surface(
        onClick = onClick,
        modifier = modifier.height(if (material3) 40.dp else 34.dp),
        color = if (selected) HongguoColors.accentSoft else HongguoColors.background,
        shape = RoundedCornerShape(if (material3) 16.dp else 8.dp),
        border = BorderStroke(1.dp,
            if (selected) HongguoColors.accent else HongguoColors.divider),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center) {
            Text(label,
                fontSize = if (material3) 12.sp else 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) HongguoColors.accent else HongguoColors.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One data-independent row for a poster, title, secondary text and status. */
internal enum class HongguoPosterRowSize { COMPACT, STANDARD }

@Composable
internal fun HongguoPosterInfoRow(
    cover: Any?,
    title: String,
    subtitle: String? = null,
    status: String? = null,
    statusHighlighted: Boolean = false,
    modifier: Modifier = Modifier,
    size: HongguoPosterRowSize = HongguoPosterRowSize.STANDARD,
    showCoverWhenBlank: Boolean = true,
) {
    val compact = size == HongguoPosterRowSize.COMPACT
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 13.dp),
        verticalAlignment = if (compact) Alignment.Top else Alignment.CenterVertically,
    ) {
        if (showCoverWhenBlank || (cover != null && cover.toString().isNotBlank())) {
            HongguoPosterCover(
                cover = cover, title = title,
                modifier = Modifier.size(width = if (compact) 57.dp else 72.dp,
                    height = if (compact) 83.dp else 102.dp),
            )
        }
        Column(
            modifier = if (compact) Modifier.weight(1f).heightIn(min = 83.dp)
                else Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 9.dp),
        ) {
            Text(title,
                fontSize = if (compact) 12.sp else 15.sp,
                lineHeight = if (compact) 15.sp else 19.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = HongguoColors.ink, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle,
                color = HongguoColors.muted,
                fontSize = if (compact) 11.sp else 12.sp,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis)
            if (!status.isNullOrBlank()) Text(status,
                fontSize = 11.sp,
                color = if (statusHighlighted) HongguoColors.accent
                    else HongguoColors.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
