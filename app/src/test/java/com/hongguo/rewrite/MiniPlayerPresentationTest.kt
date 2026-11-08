package com.hongguo.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerPresentationTest {
    private val playing = AppState(
        detail = DramaDetail(Drama(id = "123456", title = "测试短剧"),
            episodes = listOf(Episode(index = 1, vid = "987654"))),
        playbackUrl = "native://episode/987654",
        navigation = PlaybackNavigation(
            PlaybackOrigin.SWIPE_FEED, PlaybackDestination.CATALOG),
        miniPlayerVisible = true,
    )

    @Test fun explicitBrowseHomeShowsMiniAndCatalogNavigation() {
        assertTrue(MiniPlayerPresentation.show(playing))
        assertTrue(MiniPlayerPresentation.inCatalog(playing, false))
        assertTrue(MiniPlayerPresentation.catalogNavigationVisible(playing, false))
    }

    @Test fun settingsAccountAndSearchNeverShowCatalogBar() {
        for (modal in listOf(Modal.SETTINGS, Modal.ACCOUNT)) {
            val viewed = playing.copy(modal = modal)
            assertTrue(MiniPlayerPresentation.show(viewed))
            assertFalse(MiniPlayerPresentation.inCatalog(viewed, false))
            assertFalse(MiniPlayerPresentation.catalogNavigationVisible(viewed, false))
        }
        assertFalse(MiniPlayerPresentation.inCatalog(playing, true))
        assertFalse(MiniPlayerPresentation.catalogNavigationVisible(playing, true))
    }

    @Test fun detailsKeepVideoMiniButCannotDisplayHomeNav() {
        val details = playing.copy(
            navigation = playing.navigation.toSeries().toDetails(),
            detailPageVisible = true)
        assertTrue(MiniPlayerPresentation.show(details))
        assertFalse(MiniPlayerPresentation.inCatalog(details, false))
        assertFalse(MiniPlayerPresentation.catalogNavigationVisible(details, false))
    }

    @Test fun swipeFeedAndSeriesDoNotLeakLegacyHomeNav() {
        for (page in listOf(PlaybackDestination.FEED, PlaybackDestination.SERIES)) {
            val target = playing.copy(
                navigation = playing.navigation.copy(destination = page),
                miniPlayerVisible = true)
            assertFalse(MiniPlayerPresentation.show(target))
            assertFalse(MiniPlayerPresentation.catalogNavigationVisible(target, false))
        }
        assertFalse(MiniPlayerPresentation.catalogNavigationVisible(AppState(), false))
    }

    @Test fun loadingNextDramaKeepsHomeMiniOnlyWhenExplicitlyBrowsingCatalog() {
        val transitioning = playing.copy(
            preparingDetail = true, playbackUrl = null, playing = false)
        assertTrue(MiniPlayerPresentation.show(transitioning))
        assertTrue(MiniPlayerPresentation.inCatalog(transitioning, false))
        assertFalse(MiniPlayerPresentation.show(playing.copy(detail = null)))
        assertFalse(MiniPlayerPresentation.show(playing.copy(miniPlayerVisible = false)))
    }
}
