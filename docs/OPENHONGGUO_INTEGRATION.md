# openhongguo Android SDK integration audit

Source: [beilunyang/openhongguo](https://github.com/beilunyang/openhongguo),
published Android AAR `io.github.beilunyang:openhongguo:0.2.0` (Apache-2.0).
The Kotlin bridge is backed by a native Go library inside the published AAR.
This integration keeps it **anonymous, read-only and optional**.

## Architecture and constraints

- Existing `ContentRepository` + `NativeHongguoSource` remain the primary
  sources for playback (Media3), browsing, signed account features and real-time
  swipe recommendations. No official comment, like or follow **write** was added.
- `OpenHongguoSource` is initialized lazily with a distinct, no-backup
  anonymous profile. Retry budget is one attempt; automatic device resets are
  disabled. The SDK is never instantiated merely by launching the App/player.
- `OpenHongguoMetadataParser` reuses our existing
  `ContentRepository.socialDetail` signed response (the same
  `video_detail/v1` protocol inspected in upstream
  `internal/protocol/parse.go`) to extract `secondary_infos`,
  `video_relate_book.book_info`, season and verified score fields *without*
  adding another network request to playback.
- SDK `getSeries` enriches metadata **only when the user opens the detail
  page**. `seasons` requests are conditional on a real album ID.
  `seriesRecommend` is a fallback only when native related content is empty.
- Incomplete or rejected optional calls never block player setup; results
  are guarded by the currently selected series ID and request generation.
- The home recommendation remains **manual 3 cards per click**. Reels continue
  the existing official dynamic feed and type filter, not an SDK fixed catalog.
- Cover links may expire (`x-expires`); the app must re-fetch upstream
  metadata rather than altering their signed URLs.

## Source API matrix

| Upstream API | Current integration | Surface |
|---|---|---|
| `getSeries` | On-demand secondary details | Details page |
| `getSeriesBatch` | Reusable batched adapter (up to 10 IDs) | Available to call sites |
| `listEpisodes` | Existing native episode detail preferred | Existing player |
| `seasons`, `getAlbum` | SDK adapter, multi-season lookup | Details page when album ID exists |
| `seriesRecommend` | SDK fallback | Related works |
| `person`, `personWorks` | Live SDK page and pagination | Discovery > Actors > Actor works |
| `shortRank` | SDK actor, human, comic and AI boards | Discovery |
| `shortFilter`, `shortFilterPanel` | Adapter and human filter | Discovery / existing filter screen |
| `newReleases` | SDK calendar feed | Discovery > New releases |
| `hotSearch`, `searchCues`, `suggest` | Adapter and SDK hot searches | Discovery / original search UI |
| `discover`, `search` | Cursor-aware adapter; old screens retain native source | Existing catalog/search remain intact |
| `rankRows`, `rankGroups`, `ranking` | Read-only adapter | Available; activity IDs required |
| `categories`, `categoryLanding` | Read-only adapter | Available to call sites |
| `resolvePlayback`, DRM content key | Not switched to SDK | Existing native playback is tested and maintains its own stream ownership |
| `resetDevice` | Explicitly not called | Prevent anonymous device churn |

## Source credibility and parsing safety

- Type codes from `sdk/model/catalog.go`: `InfoCategory=3`,
  `InfoScore=4`, `InfoTags=9`, `InfoCreationStatus=11`,
  `InfoTwoActors=33`, `InfoPlotTag=40`.
- **Only InfoScore is a candidate for a user rating**; rank
  `Score` is often a heat/promotion metric and is never treated as 10-point
  rating. Scores must match a valid 0–10 numeric format.
- Actor name strings from `secondary_infos` are *not* a verified actor
  user ID, so no synthetic avatars, role associations or profile links are
  generated. The separate actor ranking has a genuine `user_id` and supports
  profile navigation.
- The novel comes from `video_relate_book.book_info` and is only displayed
  when a verified numeric book ID exists. Without a verified official reader
  URL, its card does not invent a novel-reading link.
- `AppCommentPages` exposes comments for an **episode**; it is not proof of
  series-level reviews. This SDK version does not expose a verified series
  review+star distribution endpoint, so the app does not falsely present the
  episode comment count as a series review count.
- Upcoming releases remain unplayable in the SDK discovery screen.
- All optional results may be absent, signature-rejected or rate-limited.
  There is no offline catalog substitution disguised as live server data.

## Current UI entry points

1. Home or ranking > **发现更多** > new releases / human / actor / comic /
   AI / hot searches / real-person filters. Server cursors advance only when
   tapping load more.
2. Actor ranking > tap a row with a verified `user_id` > public profile and
   paged works. Tap a work to use the existing native player.
3. Normal catalog poster > play directly; tap the drama name within the
   player > detail. Detail enriches optional metadata asynchronously, and
   retains local+cloud favorites, actual episode controls and related works.

## Testing / release policy

- `OpenHongguoMetadataTest` checks tags, cast, original novel,
  series-ID validation, score-vs-heat and merging.
- CI: `:app:assembleDebug :app:testDebugUnitTest --build-cache`.
- Do not build Release or Magisk ZIP; do not merge into main automatically.
- Device runtime / server availability is independent of compile-time
  verification. Test signed API access and AAR native loading on an arm64
  Android device before claiming first-party parity.
