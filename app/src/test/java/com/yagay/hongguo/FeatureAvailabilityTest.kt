package com.yagay.hongguo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import org.junit.Before

class FeatureAvailabilityTest {
    @Before fun resetFixture() {
        FeatureAvailability.unregisterProvider("test-connector")
    }
    @After fun cleanFixture() {
        FeatureAvailability.unregisterProvider("test-connector")
    }

    @Test fun verifiedImplementationMakesExistingUiSlotsAvailableAutomatically() {
        var openedFollowing = 0
        var followedName = ""
        var reviewedId = ""
        var openedRanking = false
        FeatureAvailability.registerVerifiedProvider("test-connector",
            VerifiedRemoteFeatures(
                openFollowingReels = { openedFollowing++ },
                followActorByName = { followedName = it },
                openReviewComposer = { reviewedId = it },
                openReservationRanking = { openedRanking = true },
                accountSocialCounts = RemoteSocialCounts(
                    following = 12, followers = 345, likes = 6789),
            ))
        for (feature in RemoteFeature.entries) {
            assertTrue(FeatureAvailability.hasApi(feature))
        }
        FeatureAvailability.followingAction()?.invoke()
        FeatureAvailability.actorFollowAction()?.invoke("演员")
        FeatureAvailability.reviewComposerAction()?.invoke("series-12")
        FeatureAvailability.reservationRankingAction()?.invoke()
        assertTrue(openedFollowing == 1)
        assertTrue(followedName == "演员")
        assertTrue(reviewedId == "series-12")
        assertTrue(openedRanking)
        assertTrue(FeatureAvailability.socialCounts()?.followers == 345L)

        FeatureAvailability.unregisterProvider("test-connector")
        for (feature in RemoteFeature.entries) {
            assertFalse(FeatureAvailability.hasApi(feature))
        }
    }

    @Test fun providerRegistrationNeverInventsUnsupportedOperations() {
        FeatureAvailability.registerVerifiedProvider(
            "test-connector", VerifiedRemoteFeatures(
                openFollowingReels = { /* verified provider action */ },
            ))
        assertTrue(FeatureAvailability.hasApi(RemoteFeature.FOLLOWING_REEL_FEED))
        assertFalse(FeatureAvailability.hasApi(RemoteFeature.ACTOR_FOLLOW))
        assertFalse(FeatureAvailability.hasApi(RemoteFeature.PUBLISH_REVIEW))
        assertFalse(FeatureAvailability.hasApi(RemoteFeature.RESERVATION_RANKING))
        assertFalse(FeatureAvailability.hasApi(RemoteFeature.ACCOUNT_SOCIAL_COUNTS))
    }

    @Test fun remoteFeaturesWithoutVerifiedEndpointsStayHidden() {
        for (feature in RemoteFeature.entries) {
            assertFalse(
                "No unverified API may become a visible control: $feature",
                FeatureAvailability.hasApi(feature),
            )
        }
    }

    @Test fun sdkDiscoveryIncludesWorkingNavigationTargets() {
        assertTrue(FeatureAvailability.browseEntryActionable(
            tab = "hot", rankCell = "", id = "topic:123",
            searchQuery = "热播短剧", actorUserId = "", playable = false))
        assertTrue(FeatureAvailability.browseEntryActionable(
            tab = "actor", rankCell = "", id = "actor456",
            searchQuery = "", actorUserId = "actor456", playable = false))
        assertTrue(FeatureAvailability.browseEntryActionable(
            tab = "rankGroups", rankCell = "", id = "rank001",
            searchQuery = "", actorUserId = "", playable = false))
        assertTrue(FeatureAvailability.browseEntryActionable(
            tab = "new", rankCell = "", id = "12345",
            searchQuery = "", actorUserId = "", playable = true))
    }

    @Test fun informationalOrUnplayableItemsHaveNoDeadActions() {
        assertFalse(FeatureAvailability.browseEntryActionable(
            tab = "new", rankCell = "", id = "12345",
            searchQuery = "", actorUserId = "", playable = false))
        assertFalse(FeatureAvailability.browseEntryActionable(
            tab = "new", rankCell = "", id = "not-a-series",
            searchQuery = "", actorUserId = "", playable = true))
        assertFalse(FeatureAvailability.browseEntryActionable(
            tab = "rankGroups", rankCell = "already-selected", id = "rank001",
            searchQuery = "", actorUserId = "", playable = false))
    }
}
