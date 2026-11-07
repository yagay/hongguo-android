package com.hongguo.android

import com.hongguo.api.service.ProgressiveMedia

data class DramaCard(
    val id: String,
    val title: String,
    val cover: String,
    val episodeCount: Int,
    val summary: String,
)

data class Episode(val id: String, val index: Int, val title: String)

data class DramaDetail(
    val id: String,
    val title: String,
    val cover: String,
    val summary: String,
    val episodes: List<Episode>,
)

data class DramaPage(val items: List<DramaCard>, val hasMore: Boolean, val nextOffset: Int)
data class PreparedVideo(
    val episodeId: String,
    val path: String,
    val requestId: Long = 0,
    val session: ProgressiveMedia.Session? = null,
)
