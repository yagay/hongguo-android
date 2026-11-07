package com.hongguo.rewrite

data class Drama(
    val id: String,
    val title: String,
    val cover: String = "",
    val total: Int = 0,
    val synopsis: String = "",
)
data class Episode(val index: Int, val vid: String, val title: String = "")
data class DramaDetail(val drama: Drama, val episodes: List<Episode>)
data class CatalogPage(val items: List<Drama>, val hasMore: Boolean, val nextOffset: Int)
data class CommentEntry(
    val id: String,
    val author: String,
    val content: String,
    val likes: Long,
    val created: Long,
    val avatar: String = "",
)
data class CommentsPage(
    val items: List<CommentEntry>,
    val nextCursor: String = "",
    val hasMore: Boolean = false,
    val limited: Boolean = false,
)
data class DanmakuEntry(val id: String, val content: String, val timeMs: Long)
data class DanmakuPage(val items: List<DanmakuEntry>, val nextMs: Long)

data class FilterOption(val value: String, val label: String)
data class FilterGroup(val key: String, val name: String, val options: List<FilterOption>)
data class Watched(val drama: Drama, val episode: Int, val updated: Long)

enum class Section(val title: String) {
    RECOMMEND("推荐"), RANK("榜单"), LATEST("最新"), BROWSE("筛选"), SEARCH("搜索")
}
enum class Modal { NONE, HISTORY, FAVORITES, SETTINGS, SHARE, DOWNLOAD }
data class DownloadTask(
    val id: String,
    val status: String,
    val done: Int,
    val total: Int,
    val failed: List<Int>,
)
data class AppState(
    val section: Section = Section.RECOMMEND,
    val search: String = "",
    val genre: String = "short_play",
    val board: String = "all",
    val filters: List<FilterGroup> = emptyList(),
    val activeFilters: Map<String, String> = emptyMap(),
    val items: List<Drama> = emptyList(),
    val nextOffset: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val moreLoading: Boolean = false,
    val error: String? = null,
    val modal: Modal = Modal.NONE,
    val detail: DramaDetail? = null,
    val episodeIndex: Int = 0,
    val preparingDetail: Boolean = false,
    val playing: Boolean = false,
    val playbackUrl: String? = null,
    val playbackError: String? = null,
    val library: List<Watched> = emptyList(),
    val favorites: List<Watched> = emptyList(),
    val downloadTasks: List<DownloadTask> = emptyList(),
    val backend: String = "",
    val settingsMessage: String? = null,
    val logEnabled: Boolean = true,
    val logText: String = "",
    val logNotice: String? = null,
    val danmakuEnabled: Boolean = false,
    val commentsForVid: String? = null,
    val comments: List<CommentEntry> = emptyList(),
    val commentsCursor: String = "",
    val commentsHasMore: Boolean = false,
    val commentsLoading: Boolean = false,
    val commentsError: String? = null,
    val commentsLimited: Boolean = false,
    val danmakuForVid: String? = null,
    val danmakuItems: List<DanmakuEntry> = emptyList(),
    val danmakuLoadedUntilMs: Long = 0L,
    val danmakuLoading: Boolean = false,
    val danmakuError: String? = null,
)
