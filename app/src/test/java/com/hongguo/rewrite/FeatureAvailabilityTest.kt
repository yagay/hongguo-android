package com.hongguo.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureAvailabilityTest {
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
