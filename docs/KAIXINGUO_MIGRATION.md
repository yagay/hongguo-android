# Kaixinguo direct playback / catalog migration (rewrite-web-native)

The current rewrite preserves its UI, account-aware official recommendation feed,
navigation, one service-owned Media3 ExoPlayer, playback notification,
background controls, picture-in-picture and mini-player.

## Active video pipeline

1. \`OpenHongguoSource.playbackModel(vid)\` resolves the fresh authorized
   progressive MP4 URL, backup URL and any supplied content key.
2. \`KxgPlaybackStreams\` chooses a quality and registers the addresses/key
   in Kaixinguo's bounded in-process \`StreamRegistry\`.
3. \`BackgroundPlaybackService\` creates a Media3 \`ProgressiveMediaSource\`
   with \`KxgPlaybackStreams.factory\` and the ported \`CencDataSource\`.
4. The actual Media3 loading thread probes the MP4 ranges and decrypts
   CENC bytes on demand, like the upstream Kaixinguo \`ExoVideo\` implementation.
5. The single MediaSessionService player continues playback in the background
   without creating extra decoder instances.

There is NO persistent decrypted-video \`SimpleCache\`, \`CacheDataSource\`,
legacy native-TV preload manager, speculative Kaixinguo video-model preloader,
or recent \`RemoteVideo\` handle pool. The ephemeral stream registry keeps at
most three entries (like Kaixinguo) for an active/nearby video. Cache URLs and
keys are not stored in local preferences. On app startup the known old
\`cacheDir/kaixinguo_cenc_cache_v1\` and \`cacheDir/native_stream_cache_v1\`
directories are deleted if present. This does not touch saved offline
downloads, watch history, account sessions or covers.

The official/anonymous recommendation QUEUE still requests new catalog entries
ahead of the swipe boundary. That network feed pagination is NOT a video cache;
the player resolves each selected video's current signed model normally.

The optional custom HTTP backend and native \`RemoteVideo\` compatibility
transport are left available, but use direct data sources rather than the old
disk/video preload caches.

## Expected trade-off

Removing persistent video caches and overlapping preloads simplifies failure
handling, invalidation and signed-stream lifetime. Replays and uncached new
episodes still need a fresh model, MP4 probe and network ranges, and may start
more slowly than when a healthy preload was available. This is not a claim of
seamless or offline playback.

## Debug verification

\`\`\`bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --build-cache
\`\`\`

Check opening and seeking an episode, rapid forward/reverse reel swipes,
account-aware recommendations, a finished series' next drama, signed URL
expiry, background audio/notification, PiP and custom backend streams.
Diagnostics include \`PLAYBACK/UPSTREAM_KXG_DIRECT_SOURCE\`,
\`PLAYBACK/KXG_STREAM_ACQUIRED\` and
\`PLAYBACK/KXG_DIRECT_CENC_SOURCE\`. Never log signed CDN URLs or keys.

Kaixinguo CENC/Streams components are MIT-derived, attributed in
\`THIRD_PARTY.md\`; this repo remains GPL-3.0-only.
