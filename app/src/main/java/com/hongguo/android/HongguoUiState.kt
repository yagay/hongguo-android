package com.hongguo.android

import com.hongguo.account.protocol.RankBoard
import com.hongguo.account.protocol.RankEntry
import com.hongguo.account.protocol.RelatedWork

/** SETTINGS and THEME_STYLE are pages of their own (no bottom bar), opened from 我的 and closed back to where they came from. */
enum class AppTab { HOME, CATEGORY, SEARCH, FAVORITES, MINE, HISTORY, ACCOUNT, SETTINGS, THEME_STYLE, RANK, PLAY_HISTORY, LOCAL_FAVORITES }

data class FeedState(
    val items: List<DramaCard> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val nextOffset: Int = 0,
    val error: String? = null,
    val loaded: Boolean = false,
)

data class HongguoUiState(
    val tab: AppTab = AppTab.HOME,
    val homeFeed: HomeFeed = HomeFeed.GUESS,
    /** 播放历史 page: this device's records rather than the signed-in account's. */
    val playHistoryLocal: Boolean = false,
    val home: FeedState = FeedState(),
    val category: FeedState = FeedState(),
    val browseDraft: BrowseSelection = BrowseSelection(),
    val browseApplied: BrowseSelection = BrowseSelection(),
    val filterGroups: List<FilterGroup> = emptyList(),
    val filtersLoading: Boolean = false,
    val filtersError: String? = null,
    val favorites: List<LibraryEntry> = emptyList(),
    val history: List<LibraryEntry> = emptyList(),
    val libraryCloud: Boolean = false,
    val query: String = "",
    val searchedQuery: String = "",
    val results: List<DramaCard> = emptyList(),
    val searching: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val nextOffset: Int = 0,
    val searchError: String? = null,
    val selectedCard: DramaCard? = null,
    val detail: DramaDetail? = null,
    val loadingDetail: Boolean = false,
    val detailError: String? = null,
    /** Other seasons and same-IP works of the selected drama; empty when unavailable. */
    val related: List<RelatedWork> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val rankBoard: RankBoard = RankBoard.ALL,
    val rankEntries: List<RankEntry> = emptyList(),
    val rankPage: Int = 0,
    val rankTotalPages: Int = 0,
    val rankLoading: Boolean = false,
    val rankError: String? = null,
    val episodePosition: Int = -1,
    val preparing: Boolean = false,
    val preparationStage: String = "",
    val preparationDone: Long = 0,
    val preparationTotal: Long = 0,
    val video: PreparedVideo? = null,
    val playbackError: String? = null,
    val playbackDiagnostic: PlaybackDiagnostic? = null,
    val playbackStatus: String = "",
    val firstFrameRendered: Boolean = false,
    val resumePositionMs: Long = 0,
    val playerOpen: Boolean = false,
    val autoPlayNext: Boolean = true,
    val preloadNext: Boolean = true,
    val playbackSpeed: Float = 1f,
    val themeStyle: AppThemeStyle = AppThemeStyle.WARM,
    val themeMode: ThemeMode? = null,
    val navBarStyle: NavBarStyle = NavBarStyle.CLASSIC,
    val themeOnboardingPending: Boolean = false,
    /** Confirmation after the first-launch picker; cleared only once the snackbar has been shown. */
    val themeHint: String? = null,
)

