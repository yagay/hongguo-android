package com.hongguo.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShellNavigationPolicyTest {
    private val detail = DramaDetail(
        Drama(id = "123456", title = "历史记录中的剧"),
        episodes = listOf(Episode(index = 1, vid = "987654")),
    )

    private fun series(origin: PlaybackOrigin) = AppState(
        detail = detail,
        playbackUrl = "native://episode/987654",
        navigation = PlaybackNavigation(origin, PlaybackDestination.SERIES),
        miniPlayerVisible = false,
    )

    @Test fun historyAndTheaterOpenTheSameFullPlayer() {
        val history = series(PlaybackOrigin.CATALOG)
        val theater = series(PlaybackOrigin.SWIPE_FEED)
        for (state in listOf(history, theater)) {
            assertEquals(AppShellNavigationPolicy.VideoLayer.FULL,
                AppShellNavigationPolicy.videoLayer(state))
            assertTrue(AppShellNavigationPolicy.shouldSelectHomeForNewSeries(state))
            assertTrue(AppShellNavigationPolicy.shouldMinimizeForTab(state, "theater"))
            assertTrue(AppShellNavigationPolicy.shouldMinimizeForTab(state, "mine"))
            assertFalse(AppShellNavigationPolicy.shouldMinimizeForTab(state, "home"))
        }
    }

    @Test fun miniPlaybackIsIndependentOfSelectedTabAndResumesExistingItem() {
        val mini = series(PlaybackOrigin.CATALOG).copy(
            miniPlayerVisible = true,
            navigation = PlaybackNavigation(PlaybackOrigin.CATALOG,
                PlaybackDestination.CATALOG),
        )
        assertEquals(AppShellNavigationPolicy.VideoLayer.MINI,
            AppShellNavigationPolicy.videoLayer(mini))
        assertTrue(AppShellNavigationPolicy.shouldRestoreForHome(mini))
        assertFalse(AppShellNavigationPolicy.shouldMinimizeForTab(mini, "mine"))
        assertFalse(AppShellNavigationPolicy.shouldSelectHomeForNewSeries(mini))
    }

    @Test fun detailsKeepTheUnderlyingVideoWithoutPretendingToBeFullTab() {
        val details = series(PlaybackOrigin.CATALOG).copy(
            navigation = PlaybackNavigation(PlaybackOrigin.CATALOG,
                PlaybackDestination.SERIES).toDetails(),
            detailPageVisible = true,
            miniPlayerVisible = true,
        )
        assertEquals(AppShellNavigationPolicy.VideoLayer.BEHIND_DETAILS,
            AppShellNavigationPolicy.videoLayer(details))
        assertFalse(AppShellNavigationPolicy.shouldMinimizeForTab(details, "mine"))
        assertFalse(AppShellNavigationPolicy.shouldSelectHomeForNewSeries(details))
    }

    @Test fun feedLoadingDoesNotOverrideTheaterOrMy() {
        val loading = AppState(
            navigation = PlaybackNavigation().toFeed(),
            detail = null,
        )
        assertEquals(AppShellNavigationPolicy.VideoLayer.NONE,
            AppShellNavigationPolicy.videoLayer(loading))
        assertTrue(AppShellNavigationPolicy.shouldShowLoading(loading, "home"))
        assertFalse(AppShellNavigationPolicy.shouldShowLoading(loading, "theater"))
        assertFalse(AppShellNavigationPolicy.shouldShowLoading(loading, "mine"))
    }

    @Test fun noContentCannotProduceMiniOrRestore() {
        val empty = series(PlaybackOrigin.CATALOG).copy(detail = null)
        assertEquals(AppShellNavigationPolicy.VideoLayer.NONE,
            AppShellNavigationPolicy.videoLayer(empty))
        assertFalse(AppShellNavigationPolicy.shouldRestoreForHome(empty))
    }
}
