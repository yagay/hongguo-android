package com.yagay.hongguo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPiPPolicyTest {
    private fun eligible(
        detail: Boolean = true,
        preparing: Boolean = false,
        source: Boolean = true,
        requested: String? = "ep-2",
        current: String? = "ep-2",
        playing: Boolean = true,
    ) = VideoPiPPolicy.canAutoEnter(
        detail, preparing, source, requested, current, playing)

    @Test fun playingMatchingVideoCanAutoEnterCompactPortraitPiP() {
        assertTrue(eligible())
        assertTrue(VideoPiPPolicy.PORTRAIT_WIDTH * 16 ==
            VideoPiPPolicy.PORTRAIT_HEIGHT * 9)
    }

    @Test fun pausedOrPreviousMediaMustNotAutoEnter() {
        assertFalse(eligible(playing = false))
        assertFalse(eligible(current = "ep-1"))
        assertFalse(eligible(current = null))
        assertFalse(eligible(requested = null))
        assertFalse(eligible(requested = ""))
    }

    @Test fun autoToggleDisablesOnlyAutoPipDespiteGrantedSystemPermission() {
        assertFalse(VideoPiPPolicy.isAutoPipAllowed(
            false, VideoPiPAccess.Status.ALLOWED))
        assertFalse(VideoPiPPolicy.isAutoPipAllowed(
            false, VideoPiPAccess.Status.UNKNOWN))
        assertTrue(VideoPiPPolicy.isAutoPipAllowed(
            true, VideoPiPAccess.Status.ALLOWED))
    }

    @Test fun systemRestrictionStillBlocksEnabledInAppToggle() {
        assertFalse(VideoPiPPolicy.isAutoPipAllowed(
            true, VideoPiPAccess.Status.BLOCKED))
        assertFalse(VideoPiPPolicy.isAutoPipAllowed(
            true, VideoPiPAccess.Status.UNSUPPORTED))
    }

    @Test fun manualPipWorksEvenWhenAutoPipIsOff() {
        val granted = VideoPiPAccess.Status.ALLOWED
        assertFalse(VideoPiPPolicy.isAutoPipAllowed(false, granted))
        assertTrue(VideoPiPPolicy.isManualEntryPermitted(granted))
        assertTrue(VideoPiPPolicy.showManualButton(true))
        assertFalse(VideoPiPPolicy.showManualButton(false))
    }

    @Test fun hidingManualButtonDoesNotDisableAutomaticPip() {
        val granted = VideoPiPAccess.Status.ALLOWED
        assertFalse(VideoPiPPolicy.showManualButton(false))
        assertTrue(VideoPiPPolicy.isAutoPipAllowed(true, granted))
    }

    @Test fun manualEntryStillRespectsSystemPermission() {
        assertFalse(VideoPiPPolicy.isManualEntryPermitted(
            VideoPiPAccess.Status.BLOCKED))
        assertFalse(VideoPiPPolicy.isManualEntryPermitted(
            VideoPiPAccess.Status.UNSUPPORTED))
        assertTrue(VideoPiPPolicy.isManualEntryPermitted(
            VideoPiPAccess.Status.UNKNOWN))
    }

    @Test fun activeMedia3PlayerCanAutoEnterWithoutViewModelPlaybackUrl() {
        assertTrue(VideoPiPPolicy.canAutoEnterFromActivePlayer(
            isPlayerVisible = true, actualMediaId = "episode-42",
            isPlaying = true,
        ))
        assertFalse(VideoPiPPolicy.canAutoEnterFromActivePlayer(
            isPlayerVisible = false, actualMediaId = "episode-42",
            isPlaying = true,
        ))
        assertFalse(VideoPiPPolicy.canAutoEnterFromActivePlayer(
            isPlayerVisible = true, actualMediaId = null,
            isPlaying = true,
        ))
        assertFalse(VideoPiPPolicy.canAutoEnterFromActivePlayer(
            isPlayerVisible = true, actualMediaId = "episode-42",
            isPlaying = false,
        ))
    }

    @Test fun homeAutoPipDoesNotDependOnManualButtonVisibility() {
        assertTrue(VideoPiPPolicy.isAutoPipAllowed(
            true, VideoPiPAccess.Status.ALLOWED))
        assertFalse(VideoPiPPolicy.showManualButton(false))
        assertTrue(VideoPiPPolicy.canAutoEnterFromActivePlayer(
            true, "episode-42", true))
        assertFalse(VideoPiPPolicy.isAutoPipAllowed(
            false, VideoPiPAccess.Status.ALLOWED))
    }

    private fun leaving(
        autoEnabled: Boolean = true,
        playerVisible: Boolean = true,
        playing: Boolean = true,
        inPip: Boolean = false,
        requested: Boolean = false,
        settings: Boolean = false,
        finishing: Boolean = false,
        access: VideoPiPAccess.Status = VideoPiPAccess.Status.ALLOWED,
    ) = VideoPiPPolicy.canEnterWhenLeavingApp(
        autoPipEnabled = autoEnabled,
        systemAccess = access,
        isPlayerVisible = playerVisible,
        actualMediaId = "episode-42",
        isPlaying = playing,
        alreadyInPip = inPip,
        entryRequested = requested,
        openingSettings = settings,
        finishingOrRecreating = finishing,
    )

    @Test fun externalAppSwitchAndHomeShareTheSameEligibility() {
        assertTrue(leaving())
        assertFalse(leaving(autoEnabled = false))
        assertFalse(leaving(playerVisible = false))
        assertFalse(leaving(playing = false))
        assertFalse(leaving(access = VideoPiPAccess.Status.BLOCKED))
    }

    @Test fun pauseDoesNotDuplicatePipOrOpenOnPermissionSettings() {
        assertFalse(leaving(inPip = true))
        assertFalse(leaving(requested = true))
        assertFalse(leaving(settings = true))
        assertFalse(leaving(finishing = true))
    }

    @Test fun manualButtonVisibilityDoesNotGateAppSwitchAutoPip() {
        assertFalse(VideoPiPPolicy.showManualButton(false))
        assertTrue(leaving(autoEnabled = true))
    }

    @Test fun noActiveSourceOrDetailsDoesNotAutoEnter() {
        assertFalse(eligible(detail = false))
        assertFalse(eligible(preparing = true))
        assertFalse(eligible(source = false))
    }
}
