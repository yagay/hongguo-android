package com.hongguo.rewrite

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DataSource
import com.hongguotv.core.RemoteVideo
import androidx.media3.ui.PlayerView

@Composable
internal fun NativePlayer(state: AppState, model: DramaViewModel) {
    val detail = state.detail ?: return
    var speed by remember { mutableFloatStateOf(1f) }
    var showEpisodes by remember { mutableStateOf(false) }
    var pendingDownloadVid by rememberSaveable { mutableStateOf<String?>(null) }
    val nativeFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4")
    ) { uri ->
        val vid = pendingDownloadVid
        pendingDownloadVid = null
        if (uri != null && vid != null) model.downloadNative(vid, uri)
    }
    val selectedEpisode = detail.episodes.getOrNull(state.episodeIndex)
    BackHandler { model.closePlayer() }
    Column(Modifier.fillMaxSize().background(Color(0xFF151315))) {
        Row(Modifier.fillMaxWidth().padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = model::closePlayer) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Color.White)
            }
            Text(detail.drama.title, color = Color.White,
                modifier = Modifier.weight(1f), maxLines = 1)
            TextButton(onClick = model::favorite) {
                val fav = state.favorites.any { it.drama.id == detail.drama.id }
                Text(if (fav) "★ 已收藏" else "☆ 收藏", color = Color.White)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val url = state.playbackUrl
            if (url != null) {
                Media3Player(url, speed, model.nativeVideoFor(selectedEpisode?.vid.orEmpty()),
                    onFirstFrame = { model.firstFrame(selectedEpisode?.vid.orEmpty()) },
                    onFinished = { model.nextEpisode(selectedEpisode?.vid.orEmpty()) },
                    onError = { message -> model.mediaError(selectedEpisode?.vid.orEmpty(), message) })
            } else if (state.playbackError == null) {
                CircularProgressIndicator()
            }
            state.playbackError?.let { error ->
                Column(Modifier.padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("当前剧集播放失败", color = Color.White)
                    Text(error, color = Color(0xFFD1C7C3))
                    OutlinedButton(onClick = model::retryPlayback) {
                        Icon(Icons.Outlined.Refresh, null)
                        Text("重新尝试")
                    }
                }
            }
        }
        // Bounded, scrollable controls: expanded episodes or large font sizes
        // must not push the final row below the gesture navigation area.
        Column(Modifier.fillMaxWidth().heightIn(max = 255.dp)
            .verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(selectedEpisode?.let { "第 " + it.index + " 集" } ?: "暂无剧集",
                color = Color.White)
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showEpisodes = !showEpisodes }) {
                    Text(if (showEpisodes) "收起选集" else "选集 · 共 " + detail.episodes.size + " 集",
                        color = Color.White)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    selectedEpisode?.let { ep ->
                        if (state.backend.isBlank()) {
                            pendingDownloadVid = ep.vid
                            val safeTitle = detail.drama.title.replace(
                                Regex("""[^\p{L}\p{N}_-]"""), "_"
                            ).take(35).ifBlank { "Hongguo" }
                            nativeFilePicker.launch(safeTitle + "-EP" + ep.index + ".mp4")
                        } else {
                            model.download(ep.index, ep.index)
                        }
                    }
                }, enabled = selectedEpisode != null) { Text("下载本集", color = Color.White) }
                TextButton(onClick = model::nextEpisode) { Text("下一集", color = Color.White) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                    FilterChip(selected = speed == value, onClick = { speed = value },
                        label = { Text(value.toString() + "×") })
                }
            }
            if (showEpisodes) {
                LazyVerticalGrid(columns = GridCells.Fixed(5),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 216.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(detail.episodes) { index, episode ->
                        FilterChip(selected = index == state.episodeIndex,
                            onClick = { model.play(index) }, label = { Text(episode.index.toString()) })
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun Media3Player(
    url: String, speed: Float, nativeVideo: RemoteVideo?,
    onFirstFrame: () -> Unit,
    onFinished: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val player = remember(url, nativeVideo) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() = onFirstFrame()
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onFinished()
            }
            override fun onPlayerError(error: PlaybackException) {
                onError(PlaybackProblem.media(error.errorCodeName, error.cause?.javaClass?.simpleName))
            }
        }
        player.addListener(listener)
        if (nativeVideo != null) {
            val factory = DataSource.Factory { NativeVideoDataSource(nativeVideo) }
            val mediaSource = ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(url))
            player.setMediaSource(mediaSource)
        } else {
            player.setMediaItem(MediaItem.fromUri(url))
        }
        player.prepare()
        player.playWhenReady = true
        onDispose {
            player.removeListener(listener)
            player.release()
            nativeVideo?.close()
        }
    }
    LaunchedEffect(player, speed) { player.setPlaybackSpeed(speed) }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                this.player = player
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)
            }
        },
        modifier = Modifier.fillMaxSize().testTag("native_player"),
        update = { it.player = player },
    )
}
