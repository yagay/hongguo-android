package com.hongguo.rewrite

/**
 * Display a remote action only after its endpoint and invocation are
 * implemented and verified. A transient network error or an empty response
 * does NOT make an otherwise supported feature disappear.
 *
 * This is intentionally independent of login state: an unverified feature
 * must not appear as if it works just because an account is connected.
 */
internal enum class RemoteFeature {
    FOLLOWING_REEL_FEED,
    ACTOR_FOLLOW,
    PUBLISH_REVIEW,
    RESERVATION_RANKING,
    ACCOUNT_SOCIAL_COUNTS,
}

internal object FeatureAvailability {
    // These actions currently have no verified request/response contract.
    // Do not enable a feature until its real operation is connected.
    private val verifiedRemoteActions = emptySet<RemoteFeature>()

    fun hasApi(feature: RemoteFeature): Boolean =
        feature in verifiedRemoteActions

    /**
     * A discovery row may link to a search, an actor, a rank group, or a
     * playable series. An item without any of these paths is informational,
     * not an actionable button; omit it from actionable browse results.
     */
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
