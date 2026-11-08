package com.hongguo.rewrite

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

/** Account drawer over the existing reel surface; it never owns or rebuilds Media3. */
@Composable
internal fun ReelAccountDrawer(
    state: AppState,
    model: DramaViewModel,
    onAccount: () -> Unit,
    onHistoryItem: (Drama) -> Unit,
    onReservations: () -> Unit,
    onDownloads: () -> Unit,
) {
    val account by model.account.state.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF8F8F8))
            .testTag("reel_account_drawer"),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 24.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onAccount)
                    .padding(horizontal = 8.dp, vertical = 12.dp)
                    .testTag("drawer_account"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val avatar = account.profile?.avatarUrl
                if (!avatar.isNullOrBlank()) {
                    AsyncImage(
                        model = avatar,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(CircleShape),
                    )
                } else {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(56.dp).clip(CircleShape)
                            .background(Color(0xFFE8E8E8)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.PersonOutline, null,
                            tint = Color.DarkGray, modifier = Modifier.size(30.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(account.profile?.nickname ?: "登录红果账号",
                        color = Color(0xFF161616), fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("账号与隐私", color = Color(0xFF777777), fontSize = 12.sp)
                }
                Icon(Icons.Outlined.ChevronRight, null, tint = Color.Gray)
            }
        }
        item {
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("最近在看", fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp, color = Color(0xFF222222))
                    Spacer(Modifier.height(12.dp))
                    if (state.library.isEmpty()) {
                        Text("还没有观看记录", color = Color.Gray, fontSize = 13.sp)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            state.library.take(3).forEach { watched ->
                                Column(
                                    Modifier.weight(1f).clickable {
                                        onHistoryItem(watched.drama)
                                    }.testTag("drawer_recent_" + watched.drama.id),
                                ) {
                                    AsyncImage(
                                        model = model.cover(watched.drama.cover),
                                        contentDescription = watched.drama.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxWidth().height(112.dp)
                                            .clip(RoundedCornerShape(10.dp)),
                                    )
                                    Text(watched.drama.title, color = Color(0xFF222222),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontSize = 12.sp)
                                    Text("第${watched.episode}集", color = Color.Gray,
                                        fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("常用功能", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF222222))
                    DrawerAction("我的预约", Icons.Outlined.EventAvailable,
                        onReservations, "drawer_reservations")
                    DrawerAction("我的下载", Icons.Outlined.Download,
                        onDownloads, "drawer_downloads")
                    DrawerAction("账号与隐私", Icons.Outlined.PrivacyTip,
                        onAccount, "drawer_privacy")
                }
            }
        }
    }
}

@Composable
private fun DrawerAction(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    tag: String,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(vertical = 11.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(icon, null, tint = Color(0xFF222222), modifier = Modifier.size(23.dp))
        Text(title, color = Color(0xFF202020), fontSize = 15.sp,
            modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ChevronRight, null, tint = Color.LightGray)
    }
}
