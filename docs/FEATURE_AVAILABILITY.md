# Automatic visibility for verified API features

The app starts with unsupported remote actions **hidden**. This includes the following reel feed, actor follow, review publishing, reservation rankings, and account social counters. Local actions (history, watch later, downloads, offline library, search, favorites, etc.) are not gated by these remote capabilities.

`app/src/main/java/com/hongguo/rewrite/FeatureAvailability.kt` is the single source of truth.

## When a new API is integrated

1. Implement the actual API method **and its working UI navigation/action** in the app's source adapter.
2. Verify the endpoint, parameters, authentication requirements, response handling and meaningful user interaction.
3. Register a `VerifiedRemoteFeatures` binding after the client is ready:

```kotlin
FeatureAvailability.registerVerifiedProvider(
    id = "verified-official-provider",
    features = VerifiedRemoteFeatures(
        openFollowingReels = { openRealFollowingFeed() },
        followActorByName = { actorName -> followActorByName(actorName) },
        openReviewComposer = { seriesId -> showReviewComposer(seriesId) },
        openReservationRanking = { showOfficialReservationRanking() },
        accountSocialCounts = actualCounts.takeIf { isLoggedIn },
    ),
)
```

The callbacks above are **illustrative**. None of those methods is currently implemented or registered. Only register the fields that have actually been implemented and verified. Each existing screen reads the registry as Compose snapshot state, so a newly registered action appears automatically on the next recomposition, without editing each screen.

4. Update a provider's registration if real counts become available or account access changes. Call `unregisterProvider(id)` when its owner is permanently disposed to avoid stale callback references. Temporary network failures and empty result sets must **not** unregister the feature.
5. Add tests for any new handler and keep its invocation guarded by the same registry.

Do not enable a control merely because a server URL responds, a guessed endpoint exists, the user is logged in, or sample data is available. The callback must be real. A server-side API added after an APK was built cannot be used by that APK until the client integration is implemented and distributed.

The discovery list separately filters SDK rows with no usable actor, search, ranking, or playable-series destination. Empty responses are not evidence that a supported feature should disappear.
