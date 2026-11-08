package com.hongguo.rewrite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Only verified, integrated operations may reveal remote UI. The handlers
 * are part of the capability: a bare server flag can never show a dead
 * button. Offline/empty responses do not revoke support.
 */
internal enum class RemoteFeature {
    FOLLOWING_REEL_FEED,
    ACTOR_FOLLOW,
    PUBLISH_REVIEW,
    RESERVATION_RANKING,
    ACCOUNT_SOCIAL_COUNTS,
}

internal data class RemoteSocialCounts(
    val following: Long,
    val followers: Long,
    val likes: Long,
)

/**
 * A connector registers only operations that have a working implementation.
 * The UI automatically becomes visible when the provider is registered.
 * The ranking handler opens the provider's actual ranking screen; no
 * placeholder ranking list is shown.
 */
internal data class VerifiedRemoteFeatures(
    val openFollowingReels: (() -> Unit)? = null,
    val followActorByName: ((String) -> Unit)? = null,
    val openReviewComposer: ((String) -> Unit)? = null,
    val openReservationRanking: (() -> Unit)? = null,
    val accountSocialCounts: RemoteSocialCounts? = null,
)

internal object FeatureAvailability {
    // Snapshot state makes capability changes visible to active Compose pages.
    // No provider is registered for these five unverified endpoints today.
    private var providers by mutableStateOf<Map<String, VerifiedRemoteFeatures>>(emptyMap())

    /** Register after a concrete client method and its UI action both work.
     * Unregister when the client is disposed or permanently disconnected.
     * Do not unregister on transient errors, empty data, or HTTP timeouts.
     */
    fun registerVerifiedProvider(id: String, features: VerifiedRemoteFeatures) {
        require(id.isNotBlank())
        providers = providers + (id to features)
    }

    fun unregisterProvider(id: String) {
        providers = providers - id
    }

    private fun <T : Any> firstSupported(get: (VerifiedRemoteFeatures) -> T?): T? =
        providers.values.firstNotNullOfOrNull(get)

    fun followingAction(): (() -> Unit)? = firstSupported { it.openFollowingReels }
    fun actorFollowAction(): ((String) -> Unit)? = firstSupported { it.followActorByName }
    fun reviewComposerAction(): ((String) -> Unit)? = firstSupported { it.openReviewComposer }
    fun reservationRankingAction(): (() -> Unit)? =
        firstSupported { it.openReservationRanking }
    fun socialCounts(): RemoteSocialCounts? = firstSupported { it.accountSocialCounts }

    fun hasApi(feature: RemoteFeature): Boolean = when (feature) {
        RemoteFeature.FOLLOWING_REEL_FEED -> followingAction() != null
        RemoteFeature.ACTOR_FOLLOW -> actorFollowAction() != null
        RemoteFeature.PUBLISH_REVIEW -> reviewComposerAction() != null
        RemoteFeature.RESERVATION_RANKING -> reservationRankingAction() != null
        RemoteFeature.ACCOUNT_SOCIAL_COUNTS -> socialCounts() != null
    }

    /** SDK results can be informational records without a working destination. */
    fun browseEntryActionable(
        tab: String,
        rankCell: String,
        id: String,
        searchQuery: String,
        actorUserId: String,
        playable: Boolean,
    ): Boolean =
        (tab == "rankGroups" && rankCell.isBlank() && id.isNotBlank()) ||
            searchQuery.isNotBlank() ||
            actorUserId.isNotBlank() ||
            (playable && id.matches(Regex("[1-9][0-9]{0,29}")))
}
