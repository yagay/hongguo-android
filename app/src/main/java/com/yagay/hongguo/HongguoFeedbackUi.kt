package com.yagay.hongguo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Reusable, screen-agnostic loading/empty/error presentation. */
internal enum class HongguoFeedbackKind { LOADING, EMPTY, ERROR }

@Composable
internal fun HongguoFeedbackPanel(
    title: String,
    message: String? = null,
    kind: HongguoFeedbackKind,
    modifier: Modifier = Modifier,
    retryLabel: String = "重试",
    onRetry: (() -> Unit)? = null,
    tag: String = "hongguo_feedback",
) {
    val m3 = HongguoColors.isMaterial3
    val error = kind == HongguoFeedbackKind.ERROR
    val foreground = if (m3) {
        if (error) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSurface
    } else HongguoColors.ink
    Surface(
        modifier = modifier.fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag),
        shape = RoundedCornerShape(if (m3) 24.dp else HongguoUi.cardRadius),
        color = if (m3) {
            if (error) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        } else if (error) HongguoColors.accentSoft else HongguoColors.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 136.dp)
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (kind == HongguoFeedbackKind.LOADING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.5.dp,
                    color = if (m3) MaterialTheme.colorScheme.primary
                        else HongguoColors.accent,
                )
            } else {
                Icon(
                    if (error) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
                    contentDescription = null,
                    tint = if (m3) foreground else HongguoColors.accent,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(title, color = foreground,
                style = if (m3) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!message.isNullOrBlank()) {
                Text(message, color = if (m3) foreground.copy(alpha = .83f)
                    else HongguoColors.muted,
                    fontSize = if (m3) 13.sp else 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("${tag}_description"))
            }
            if (onRetry != null && kind != HongguoFeedbackKind.LOADING) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("${tag}_retry"),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Text(retryLabel)
                }
            }
        }
    }
}

/**
 * Same recovery controls for the full-screen player and loading/reels screen.
 * Countdown text is NOT a live region: announcing every second disrupts
 * TalkBack; the user can still focus it to hear the current delay.
 */
@Composable
internal fun HongguoVideoRetryActions(
    onRetry: () -> Unit,
    retryLabel: String,
    recoveryStatus: String?,
    onStopRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryTag: String = "video_retry_manual",
    statusTag: String = "video_retry_countdown",
    stopTag: String = "video_retry_stop",
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!recoveryStatus.isNullOrBlank()) {
            Surface(
                color = Color.Black.copy(alpha = .60f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.testTag(statusTag),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null,
                        tint = Color(0xFFFFD4A7), modifier = Modifier.size(18.dp))
                    Text(recoveryStatus, color = Color(0xFFFFD4A7),
                        fontSize = 13.sp, lineHeight = 18.sp,
                        maxLines = 3, textAlign = TextAlign.Center)
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = 48.dp).testTag(retryTag),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(18.dp))
                Text(retryLabel, color = Color.White)
            }
            if (!recoveryStatus.isNullOrBlank()) {
                TextButton(
                    onClick = onStopRetry,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(stopTag),
                ) { Text("停止自动重试", color = Color.White) }
            }
        }
    }
}
