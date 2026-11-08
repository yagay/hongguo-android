package com.hongguo.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerPresentationTest {
    private val playing = AppState(
        detail = DramaDetail(
            Drama(id = "123456", title = "测试短剧"),
            episodes = listOf(Episode(index = 1, vid = "987654")),
        ),
        playbackUrl = "native://episode/987654",
        miniPlayerVisible = true,
    )

    @Test fun minimizedPlayerAppearsAboveCatalogNavigation() {
        assertTrue(MiniPlayerPresentation.show(playing))
        assertTrue(MiniPlayerPresentation.inCatalog(playing, searchLandingOpen = false))
    }

    @Test fun accountSettingsSearchAndDramaDetailsKeepBottomMini() {
        for (modal in listOf(Modal.SETTINGS, Modal.ACCOUNT)) {
            assertTrue(MiniPlayerPresentation.show(playing.copy(modal = modal)))
            assertFalse(MiniPlayerPresentation.inCatalog(
                playing.copy(modal = modal), searchLandingOpen = false))
        }
        assertFalse(MiniPlayerPresentation.inCatalog(
            playing, searchLandingOpen = true))
        assertFalse(MiniPlayerPresentation.inCatalog(
            playing.copy(detailPageVisible = true), searchLandingOpen = false))
        assertTrue(MiniPlayerPresentation.show(
            playing.copy(detailPageVisible = true)))
    }

    @Test fun loadingNextDramaKeepsMiniAndCatalogNavigationVisible() {
        val transitioning = playing.copy(
            preparingDetail = true,
            playbackUrl = null,
            playing = false,
        )
        assertTrue(MiniPlayerPresentation.show(transitioning))
        assertTrue(MiniPlayerPresentation.inCatalog(
            transitioning, searchLandingOpen = false))
        assertTrue(MiniPlayerPresentation.catalogNavigationVisible(
            transitioning, searchLandingOpen = false))
    }

    @Test fun allPagesUseTheSameMiniPlayerDockWithSeparateNavbar() {
        assertTrue(MiniPlayerPresentation.catalogNavigationVisible(
            playing, searchLandingOpen = false))
        assertFalse(MiniPlayerPresentation.catalogNavigationVisible(
            playing, searchLandingOpen = true))
        assertFalse(MiniPlayerPresentation.catalogNavigationVisible(
            playing.copy(modal = Modal.SETTINGS), searchLandingOpen = false))
        assertFalse(MiniPlayerPresentation.catalogNavigationVisible(
            playing.copy(modal = Modal.ACCOUNT), searchLandingOpen = false))
        assertFalse(MiniPlayerPresentation.catalogNavigationVisible(
            playing.copy(detailPageVisible = true), searchLandingOpen = false))
        assertTrue(MiniPlayerPresentation.catalogNavigationVisible(
            AppState(), searchLandingOpen = false))
    }

    @Test fun explicitCloseOrMissingVideoHidesMini() {
        assertFalse(MiniPlayerPresentation.show(
            playing.copy(detail = null)))
        assertFalse(MiniPlayerPresentation.show(
            playing.copy(miniPlayerVisible = false)))
        assertTrue(MiniPlayerPresentation.show(
            playing.copy(preparingDetail = true)))
    }
}
