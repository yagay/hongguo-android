package com.yagay.hongguo

data class Drama(
    val id: String,
    val title: String,
    val cover: String = "",
    val total: Int = 0,
    val synopsis: String = "",
    /** Explicitly confirmed status; null must never be rendered as completed. */
    val completionStatus: String? = null,
    val actors: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
)
data class Episode(val index: Int, val vid: String, val title: String = "")
data class DramaDetail(val drama: Drama, val episodes: List<Episode>)
data class RelatedDrama(val drama: Drama, val season: String, val upcoming: Boolean)
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
    /** Server-provided total for this episode, null when unavailable. */
    val total: Long? = null,
)
data class DanmakuEntry(val id: String, val content: String, val timeMs: Long)
data class DanmakuPage(val items: List<DanmakuEntry>, val nextMs: Long)

data class FilterOption(val value: String, val label: String)
data class FilterGroup(val key: String, val name: String, val options: List<FilterOption>)
data class OfflineEpisode(
    val seriesId: String,
    val title: String,
    val cover: String,
    val episode: Int,
    val vid: String,
    val documentUri: String,
    val savedAt: Long,
)
data class Watched(val drama: Drama, val episode: Int, val updated: Long)

enum class Section(val title: String) {
    RECOMMEND("推荐"), RANK("榜单"), LATEST("最新"), BROWSE("筛选"), SEARCH("搜索")
}
enum class Modal { NONE, HISTORY, FAVORITES, SETTINGS, ACCOUNT, SHARE, DOWNLOAD }
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
    val suggestions: List<String> = emptyList(),
    val suggestionsLoading: Boolean = false,
    val recentSearches: List<String> = emptyList(),
    /** Search landing is independent from the current catalogue and player. */
    val landingRank: List<Drama> = emptyList(),
    val landingTerms: List<String> = emptyList(),
    val landingLoading: Boolean = false,
    val landingError: String? = null,
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
    val detailPageVisible: Boolean = false,
    /** Keeps the service-owned video running while browsing other in-app pages. */
    val miniPlayerVisible: Boolean = false,
    /** Authoritative Back destination and origin, independent of player surface size. */
    val navigation: PlaybackNavigation = PlaybackNavigation(),
    /** Separate one-video-per-series feed from full-series episode paging. */
    /** Official swipe feed: all recommendations or strictly real-person dramas. */
    val reelsMode: String = "all",
    val feedMode: Boolean = false,
    /** Last explicitly opened full-series page, independent of the swipe
     * recommendation queue and the currently playing reel. */
    val returnSeries: Watched? = null,
    /** The original feed item to restore after temporarily reopening a
     * different series. Null means feed and series share the same video. */
    val returnFeedItem: Watched? = null,
    /** One-shot request to show in-player search after a series-detail tag click. */
    val reelSearchRequest: Long = 0L,
    /** A separate, live official swipe stream; never use the three-card home catalog as its pager. */
    val feedItems: List<Drama> = emptyList(),
    val feedIndex: Int = 0,
    val feedLoading: Boolean = false,
    val feedHasMore: Boolean = false,
    val feedError: String? = null,
    /** User-visible handoff after the final episode; reset on next first frame. */
    val nextDramaTransition: NextDramaTransition = NextDramaTransition.NONE,
    val episodeIndex: Int = 0,
    val preparingDetail: Boolean = false,
    val relatedSeries: String? = null,
    val relatedWorks: List<RelatedDrama> = emptyList(),
    val relatedLoading: Boolean = false,
    val relatedError: String? = null,
    /** Generic recommendation plan (160 / anonymous 401); not the related-work 547 cell. */
    val guessWorks: List<Drama> = emptyList(),
    val guessesLoading: Boolean = false,
    val guessesError: String? = null,
    val playing: Boolean = false,
    val playbackUrl: String? = null,
    /** Only the first frame actually rendered for this specific media ID
     * may reveal the SurfaceView; a previous reel frame stays covered. */
    val renderedVideoVid: String? = null,
    val resumePositionMs: Long = 0L,
    val qualityLimit: Int = 0,
    /** Per upcoming video, not persistent disk storage. 8 MiB by default. */
    val preloadBufferMiB: Int = VideoPreloadPolicy.DEFAULT_MIB,
    val defaultPlaybackSpeed: Float = 1f,
    /** Full-screen playback-only display brightness: -1 follows Android system. */
    val videoBrightnessPercent: Int = -1,
    /** 0 dB is unmodified; optional loudness enhancement targets only app audio. */
    val playbackAudioBoostDb: Int = 0,
    val sleepTimerLabel: String = "关闭",
    val playbackError: String? = null,
    val library: List<Watched> = emptyList(),
    val favorites: List<Watched> = emptyList(),
    val likedDramaIds: Set<String> = emptySet(),
    /** Private locally saved watch-later entries, separate from cloud favorites. */
    val watchLater: List<Drama> = emptyList(),
    val favoriteGroups: Map<String, String> = emptyMap(),
    val completedDramaIds: Set<String> = emptySet(),
    val trackedEpisodeUpdates: Map<String, Int> = emptyMap(),
    val trackingUpdates: Boolean = false,
    val episodeUpdateNotificationsEnabled: Boolean = false,
    val trackingUpdateMessage: String? = null,
    val hiddenRecommendationIds: Set<String> = emptySet(),
    val themeStyle: String = "warm",
    val themeMode: String = "light",
    val autoPlayNext: Boolean = true,
    val preloadNext: Boolean = true,
    val skipIntroSeconds: Int = 0,
    val skipOutroSeconds: Int = 0,
    val retryPlaybackAutomatically: Boolean = true,
    val downloadTasks: List<DownloadTask> = emptyList(),
    val offlineEpisodes: List<OfflineEpisode> = emptyList(),
    val offlinePlaybackUri: String? = null,
    val offlineLibraryMessage: String? = null,
    val backend: String = "",
    val settingsMessage: String? = null,
    val logEnabled: Boolean = true,
    val logText: String = "",
    val logNotice: String? = null,
    val danmakuEnabled: Boolean = false,
    val danmakuSpeed: Int = 1,
    val danmakuDensity: Int = 4,
    val danmakuFontSize: Int = 14,
    /** Optional anonymous App detail enrichment, never required for playback. */
    val publicDetail: DramaPublicDetail? = null,
    val openHongguoMetadata: OpenHongguoMetadata? = null,
    val openHongguoSeasons: List<Drama> = emptyList(),
    val openHongguoLoading: Boolean = false,
    val socialForSeries: String? = null,
    val socialStats: SocialStats? = null,
    val socialStatsLoading: Boolean = false,
    val commentsForVid: String? = null,
    val comments: List<CommentEntry> = emptyList(),
    val commentsTotal: Long? = null,
    val commentsCursor: String = "",
    val commentsHasMore: Boolean = false,
    val commentsLoading: Boolean = false,
    val commentsError: String? = null,
    val commentsLimited: Boolean = false,
    val danmakuForVid: String? = null,
    val danmakuItems: List<DanmakuEntry> = emptyList(),
    /** Start of the currently cached timestamp window (seek-safe). */
    val danmakuWindowStartMs: Long = 0L,
    val danmakuLoadedUntilMs: Long = 0L,
    val danmakuLoading: Boolean = false,
    val danmakuError: String? = null,
)
