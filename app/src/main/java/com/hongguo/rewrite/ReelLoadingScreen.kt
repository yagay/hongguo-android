package com.hongguo.rewrite

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Dedicated full-screen reel startup and recovery surface. It never draws a
 * black overlay on a playing video: this screen is only shown before any
 * DramaDetail can be rendered in the player.
 */
@Composable
internal fun ReelLoadingScreen(
    drama: Drama?,
    coverUrl: String?,
    loading: Boolean,
    preparingSeries: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onExit: () -> Unit,
    exitLabel: String = "退出刷短剧",
) {
    val dark = Color(0xFF111216)
    val mainText = Color(0xFFF6F3F0)
    val muted = Color(0xFFACABB2)
    val accent = Color(0xFFD95132)
    val stage = if (drama != null) "episode" else "recommendation"
    var elapsedSeconds by remember(stage, drama?.id, loading) {
        mutableIntStateOf(0)
    }
    LaunchedEffect(stage, drama?.id, loading) {
        while (loading) {
            delay(1_000)
            elapsedSeconds++
        }
    }
    val failed = error != null && !loading
    val title = when {
        failed -> "暂时无法加载短剧"
        drama != null -> "正在准备播放"
        else -> "正在发现好短剧"
    }
    val description = when {
        failed -> "网络或上游接口暂时不可用，可以重新获取。"
        preparingSeries && drama != null -> "正在读取这部短剧的分集信息"
        drama != null -> "正在连接视频播放资源"
        else -> "正在读取官方短剧推荐"
    }

    Box(
        Modifier.fillMaxSize().background(dark)
            .testTag("reel_loading_screen"),
    ) {
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 25.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("红果短剧", color = accent,
                fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text("刷短剧", color = muted, fontSize = 13.sp)
        }
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth()
                .padding(horizontal = 30.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (drama != null && !coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = drama.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 132.dp, height = 178.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(Color(0xFF29292D)),
                )
                Text(drama.title, color = mainText,
                    fontSize = 15.sp, maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis)
            } else {
                Box(
                    Modifier.size(90.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF30221F)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("▶", color = accent, fontSize = 38.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(33.dp),
                    color = accent,
                    strokeWidth = 3.dp,
                )
            }
            Text(title, fontSize = 21.sp,
                color = mainText, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center)
            Text(description, fontSize = 13.sp, color = muted,
                lineHeight = 19.sp, textAlign = TextAlign.Center)
            if (failed) {
                Text(error.orEmpty(), color = Color(0xFFFFA88E),
                    fontSize = 13.sp, lineHeight = 19.sp,
                    textAlign = TextAlign.Center)
            } else if (loading && elapsedSeconds >= 10) {
                Text("已等待 ${elapsedSeconds} 秒 · 网络较慢时可重新获取",
                    color = Color(0xFFD8B7A8), fontSize = 12.sp,
                    textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onRetry,
                // Repeated requests during the first seconds can compete with
                // startup registration; after 8 seconds users can cancel/retry.
                enabled = !loading || elapsedSeconds >= 8,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF40332E),
                    disabledContentColor = Color(0xFFC8B7B1),
                ),
                shape = RoundedCornerShape(HongguoUi.controlRadius),
                modifier = Modifier.fillMaxWidth().height(48.dp)
                    .testTag("reel_loading_retry"),
            ) {
                Text(
                    if (drama != null) "重新加载这部短剧" else "重新获取推荐",
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                )
            }
            HongguoAction(
                label = exitLabel, onClick = onExit,
                modifier = Modifier.testTag("reel_loading_exit"),
                style = HongguoActionStyle.QUIET,
            )
        }
        Text(
            "如果持续加载失败，可在设置中导出诊断日志",
            color = Color(0xFF77777F), fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            textAlign = TextAlign.Center,
        )
    }
}
