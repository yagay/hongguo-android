# Kaixinguo playback/catalog migration (rewrite-web-native)

The \`rewrite-web-native\` user interface, navigation, one MediaSessionService/ExoPlayer,
account, comments, picture-in-picture and mini-player are retained. The primary
anonymous catalog and progressive-MP4 transport are now adapted from
[kadaliao/kaixinguo-android](https://github.com/kadaliao/kaixinguo-android).

## Active paths

- \`KxgContentSource\` maps SDK cursor-based discover/search/latest/rank/filter
  lists, filter options, series and episode IDs into existing \`Drama\` /
  \`DramaDetail\` / \`Episode\` models.
- \`OpenHongguoSource\` owns the anonymous SDK client, now version
  \`0.2.1-kx.1\`, the Kaixinguo fork with the Android playback signing fix.
- \`KxgPlaybackStreams\` resolves a fresh playback model, picks a playable MP4,
  acquires an authorized content key when supplied, and registers range streams.
- \`kxg/Cenc.kt\` and \`kxg/Streams.kt\` are the Kaixinguo byte-range
  CENC AES-CTR decoder and cancelable Media3 DataSource, respectively.
- \`KxgStreamPreloader\` resolves only the nearest next signed model and builds
  the shared stream index; it does **not** create another ExoPlayer.
- \`BackgroundPlaybackService.configure\` detects
  \`kaixinguo-stream://\` and feeds the original one Media3 service-owned
  ExoPlayer with a ProgressiveMediaSource backed by Kxg CENC DataSource.
  Existing metadata, session, notification, navigation and PiP flow stays intact.

## Intentional exceptions

- Authenticated "刷短剧使用登录账号" recommendations remain on the existing
  account-aware feed. The Kaixinguo SDK is anonymous, so replacing that
  transport would silently discard the personal-recommendation session.
  Anonymous recommendations now come from Kaixinguo discover/rank pages.
- If the user explicitly configures the separate HTTP backend, its
  catalog/stream contract is retained.
- Account management, public comments and social metadata still use their
  existing endpoints; they are not part of the anonymous SDK's catalog/media
  APIs.
- Native TV RemoteVideo and its old cache remain as compatibility source code
  for legacy pieces, but no longer power anonymous video playback.
  The Kaixinguo stream adapter is not wired into the previous RemoteVideo
  disk-cache/preload manager. Do not report a cache hit for that old cache
  as an SDK cache hit.
- The Kaixinguo decoder handles progressive MP4 only. HLS/DASH and episodes
  needing an unavailable key must show a recoverable failure; they must not
  switch to fake URLs or bypass content protection.

## Validation

\`\`\`bash
./gradlew :app:assembleDebug :app:testDebugUnitTest --build-cache
\`\`\`

Run on a device: anonymous discovery, search, filters, pagination, series
details, episode switch, seek, quality selection, 30-min session, last episode
to next drama, back/foreground, system PiP, player mini-screen, media
notification and accounts. Inspect exported diagnostic events
\`PLAYBACK/KXG_STREAM_ACQUIRED\`, \`PLAYBACK/KXG_CENC_SOURCE\` and
\`KXG_PRELOAD\`. Never log raw signed CDN URLs or keys.

Source copies of Kaixinguo CENC/stream code are MIT-licensed. Preserve the
upstream attribution in \`THIRD_PARTY.md\`. This repo remains GPL-3.0-only.
