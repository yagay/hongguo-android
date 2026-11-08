# Open-source-first development checklist (rewrite-web-native)

Before modifying this project, inspect relevant upstream implementations, compare their source and tests, and document why a particular approach was selected. "All projects" means all **relevant, discoverable** implementations, not a claim that the entire internet has been exhaustively audited. Do not copy code with incompatible licensing or unverified private API behavior.

## Sources reviewed for the detail-page rebuild (2026-11-08)

- [zfdang/hongguo-open-player](https://github.com/zfdang/hongguo-open-player), `src/server/native/hongguo.js`: website `seriesDetail.celebrities`, actor names/avatar/role, and `seriesSocialInfo.rating/rating_count`.
- [tsukiyo-zero/hongguoTV](https://github.com/tsukiyo-zero/hongguoTV), `kotlin-tv/core/src/main/kotlin/com/hongguotv/core/PublicComments.kt`: series-level `seriesSocialInfo.reviews`, ratings, totals, reviewer portraits, and episode-vs-series separation.
- [beilunyang/openhongguo](https://github.com/beilunyang/openhongguo), Android SDK `0.2.0`: persons, works, related series, novels. Optional read-only enhancement; not a verified follow/review write API.
- Existing `yagay/hongguo-android` main branch and rewrite `NativeWebsiteSource`, `SocialStatsParser`, `OpenHongguoSource`: preserve media, local favorites, cloud follows, episode playback, validated series IDs and user data.
- [yangbin1322/hongguo-player](https://github.com/yangbin1322/hongguo-player): actor and social feature ideas; no verified compatible write protocol was established in this pass.

## Rules for future changes

1. Search the relevant upstream Android, web, desktop and SDK repositories before implementation. Read real source and tests, not merely the README.
2. Separate the verified public/read-only data path from authenticated write actions; do not invent actor follows, rating submissions, reviews, or novel reader deep links.
3. Preserve existing app behavior, schema and build policy. Only `rewrite-web-native`, Debug APK and Debug tests unless expressly requested otherwise.
4. Add deterministic parser and state-isolation tests for each imported capability; verify series identity and filter untrusted URLs.
5. Document source attribution and third-party license obligations; prefer independently authored adapters.
6. Do not equate a committed file with a successful CI run or a device-tested experience.

## Current data boundaries

- `DramaPublicDetailParser` reads official public **series** detail and review snippets, confirmed against the requested series ID.
- Player `AppCommentPages` remains **episode**-specific; its counts must not be presented as series-wide review totals.
- Public cast `celebrity_id` is not necessarily a verified account user ID; never trigger a follow operation from that value without a documented authenticated endpoint.
- Rating and ratingCount are optional; show a neutral unavailable state instead of fabricating values or drawing them from ranking heat.
- Native browser/website APIs can fail, change or be rate-limited: detail enrichment never blocks Media3 playback.

## Screenshot fidelity notes

The 2026-11-07 user-provided screenshot establishes the target presentation: brown full-screen gradient; poster/title/tags together; compact synopsis; horizontal portrait cast with roles; dedicated series rating and swipeable review cards; verified novel; related works; fixed two-button 收藏 / 写剧评 bar. The native detail screen now follows this hierarchy. Device screenshots are still required to verify exact typography, status bars, layout scale and Insets.

Missing official write protocols are not silently simulated: local 收藏 genuinely changes the on-device favorites list; 写剧评 and 演员关注 explain that a verified official write endpoint is not available. Series review totals are sourced only from `seriesSocialInfo` and never from episode comments.

## Recommendation taxonomy (2026-11-08)

- **相关作品**: from `NativeHongguoSource.related(id)`, which uses the upstream `hongguoTV` `ContentRepository.related` / `RelatedWorks.parse` reading-plan related-work cell `show_type = 547` (or explicit cell name 相关作品). Preserve its season labels and upcoming status; do not replace with generic recommendations.
- **系列剧 · 其他季**: independently resolved from `openhongguo.seasons` or the related album. Shares a creative collection, not the same list as the official related-work cell.
- **猜你喜欢**: from `openhongguo.seriesRecommend(id)`, a distinct plan-feed request with `from=select_panel_recommend`, `scene=10`. Its preferred cell is `show_type = 160`; the SDK documents that anonymous users may instead receive a generic changing `show_type = 401` “更多好剧” cell. Label it a recommendation, never claim that generic anonymous results are personalized.
- Each flow has its own loading state, error, and list. On navigation, cancel outstanding requests, discard stale results, and prevent exact duplicate IDs across recommendation and related-work sections where possible.
- This distinction is based on inspected source implementations, not a live authenticated capture of every version of the official mobile application.

## Detail label navigation (2026-11-08)

- Every rendered genre/category chip is tappable. The detail screen forwards its exact visible label to `DramaViewModel.openTagResults`, which closes the current player session and opens the existing catalogue search with that query. This avoids inventing a category ID or a new, unverified endpoint.
- Actor name and role labels use the same search navigation as a safe fallback; they are not presented as authenticated actor-profile API links. Verified actor-ID-based navigation can replace this after a stable profile destination is available.
- All available detail labels are shown in the horizontal row (subject to the bounded twelve-tag model), instead of silently displaying only the first six. Search results depend on what the upstream source indexes and may be empty.
- A search initiated from a detail opened through My must switch the shell to catalog results instead of remaining behind the My screen.

## Search landing based on the 2026-11-08 screenshot

- A primary search icon in the `CatalogHeader` opens a dedicated full-screen black search landing, outside the home `Scaffold` so its bottom tabs are not displayed. The system status/navigation icon contrast switches to light on this page.
- Top search uses the pre-existing `setSearch` + `search` catalogue pipeline, including real autocomplete, recorded search history, clear-history and actual result navigation. No hard-coded screenshot query or histories.
- The first recommendation shelf loads the real `NativeHongguoSource.list(Section.RANK, ..., board="all")` data and renders it in a three-column poster grid. Rankings do not invent popularity scores unavailable in the `Drama` model.
- Search cues and topical keywords are read from the read-only `OpenHongguoSource.browse("hot")` SDK adapter. Failures leave text search functional.
- The actor shortcut opens the SDK's already-existing actor-ranking tab. “识剧” is not falsely presented as camera recognition until a verified API exists; an unavailable message is shown. “预约榜” is explicitly unavailable pending a validated data source.
- Prior implementations inspected: local catalogue/search/history, openhongguo's public `hotSearch` and ranking methods, and the openhongguo discovery screen. This visual update was informed by the user-supplied official mobile screenshot; it is not yet screenshot-diff validated on a real device.

## Background playback and native media controls (2026-11-08)

- Reference: official AndroidX Media3 `MediaSessionService` background-playback sample, published Media3 1.8.0 `MediaSession` and `CommandButton` source, and upstream hongguoTV `RemoteVideo` / `VideoDataSource`. This app uses a single MediaSessionService-owned ExoPlayer and does **not** start a second decoder.
- Required Android manifest flags: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK`, `ACCESS_WIFI_STATE`; service is `foregroundServiceType="mediaPlayback"`. Media3 automatically publishes the media session notification.
- The notification's title and subtitle show the verified series title, current episode index, and total episode count. The **current video's elapsed/total time and seekbar** are sourced from the actual ExoPlayer timeline, never a fabricated progress estimate.
- Back/forward media controls use session commands and `BackgroundNavigationPolicy`. Normal series: previous/next **episode**. Vertical reel feed: previous/next **drama**, with the current feed queue and pending-page behavior. Play/pause and seek affect the shared ExoPlayer session.
- When the app backgrounds or the display locks, the Activity's ON_STOP no longer pauses playback. The foreground service retains the player and encrypted native video data source, checkpoints progress every four seconds, handles the sleep timer, and keeps the network/CPU awake only while playing. Android can still interrupt playback because of audio focus, permissions, battery restrictions or process termination.
- Explicit stop/close/detail navigation releases the session; switching episode cancels the old media before the new video model is installed. Never keep a leaked second player or release the active stream just because a Compose video surface is detached.
- Integration testing required on an actual device: notification controls on Android 8/13/14/16; notification seek/elapsed/total duration; locked-screen playback and audio focus; play/pause from headset; reel/episode boundary behavior; Activity recreation and task removal; encrypted RemoteVideo stream handoff; service foreground promotion; automatic end-of-episode and wake-lock release. Static source changes are not proof of these outcomes.

## Notification and autoplay regression follow-up (2026-11-08)

Observed on a device: a media player appeared in the system media area, but not as a separate ordinary drawer notification; media switching and continuous playback were unreliable.

- On Android 13+, the quick-settings media carousel is the OS-standard media surface. A separate ordinary `NotificationCompat` progress card is now posted to its own LOW-importance channel only when `POST_NOTIFICATIONS` is granted. The user can request permission from Settings > 后台播放. The ordinary card shows the actual play clock, total time, progress bar and play/pause/previous/next PendingIntent actions. The **seekable** timeline remains the system media player's native progress control; a regular notification's progress bar is not an interactive seek slider.
- The Media3 custom previous/next buttons now carry explicit BACK/FORWARD slot preferences and availability; the regular notification commands target the running playback service directly, without a second player or a background Activity launch.
- Real background audio may advance without any visible video surface. `confirmedBackgroundPlayback` treats three seconds of progressing ExoPlayer audio as validated playback for history/checkpoint and next-media prefetch, without falsely recording a rendered video frame. A return to the foreground still receives genuine `onRenderedFirstFrame`.
- Swiping past the fetched reel queue triggers a page load **without immediately pausing the current stream**. When the new drama arrives, the existing ViewModel transition loads its media into the service. Normal episode transitions use the same single service player.
- Sanitized diagnostic events now include `BACKGROUND/SERVICE_CREATED`, `SOURCE_CONFIGURED`, `MEDIA_PREVIOUS`, `MEDIA_NEXT`, `MEDIA_ENDED`, `NOTIFICATION_PERMISSION_DENIED`, and `NOTIFICATION_BLOCKED`. The diagnostics never contain media URLs or drama titles.
- Validate foreground-service notification behavior, OPPO/OnePlus-specific notification settings, screen-off continuity, first episode/last episode, last reel awaiting discovery, audio focus, and real seekbar behavior on a phone. These changes were not confirmed by a successful current CI build in this review.

## 2026-11-08 next-episode stall and 1-second clock correction

- Checked the actual `BackgroundPlaybackService.configure()`, `Player.STATE_ENDED` callback and `DramaViewModel.closeNativeVideo()` handoff against the Media3 service-owned-player model.
- `DramaViewModel` now detaches its pointer instead of closing an encrypted `RemoteVideo` still owned by `BackgroundPlaybackService`. The service closes the prior source **after** it installs and starts the replacement (or on explicit stop).
- The Media3 `STATE_ENDED` callback schedules navigation on the next main-loop turn to avoid reentrant `stop()/setMediaSource()` while ExoPlayer dispatches listeners; it skips stale transitions when the media key has already changed.
- Explicit autoplay is armed before `prepare()`, followed by `play()`; a bounded `STATE_READY` recovery attempts to restore playback if the expected autoplay intent was lost. Playback events record `NEXT_SOURCE_PLAY_REQUESTED`, `AUTO_START_RECOVERY`, and `PLAYING`.
- Do not checkpoint the old stream after it has reached `STATE_ENDED`; that would recreate a completed-episode resume point after `finishEpisode` cleared it.
- The **ordinary** notification calls its time/progress refresh every 1000 ms; updates are skipped if the media second is unchanged while playing. MediaSession controls manage their own native clock. Android/OxygenOS can still throttle/redraw notifications independently, so exactly one visible redraw per second cannot be guaranteed.
- This is static-code remediation pending Debug CI and phone testing, especially next episode with screen on/off and end-of-series autoplay.

## Playback follow-up from 2026-11-08 02:57 diagnostics

- In the uploaded diagnostic trace, automatic next-episode navigation had `MEDIA_ENDED` at 02:57:13.838, `NEXT_EPISODE` at 02:57:13.848, `AUTO_START_RECOVERY` at 02:57:13.997 and `PLAYING` at 02:57:14.002 (UTC). `FIRST_FRAME` was later at 02:57:29.146. Thus the trace confirms a temporarily lost autoplay intent and late first-frame rendering, but it does **not** measure the exact audio gap while no video surface is attached.
- The service now records sanitized reasons for changes to `playWhenReady` (user/audio-focus/noisy/remote/other), Media3 `IDLE/BUFFERING/READY/ENDED` transitions, a bounded stalled-progress event, and the caller-specific service stop reason. It never records original stream URLs or search/title text.
- Prefetch retries once after 1.5 s **only** for the typed upstream `EmptyPlaybackResponseException`; other errors are not retried, and both episode generation and quality constraints are revalidated before retrying.
- A restored playback position beyond 3 seconds does not by itself constitute background playback confirmation: the service now requires 3 seconds of newly observed forward progress.
- Keep the Android/OnePlus notification and MediaSession timeline independent of the UI lifecycle. The real reason for `AUTO_START_RECOVERY` is pending capture by these new diagnostics; do not assume one cause without a new trace.
- CI run and lock-screen device validation are still pending.

## Media card disappearing + returning to permanently loading player (2026-11-08)

The user reports **both** loss of background audio and removal of the OS media card, then permanent loading/failure on returning to the app. This suggests loss of the player/service itself, not merely ordinary paused playback; it is **not** proof of an OxygenOS kill, because the current diagnostic export does not capture the stop instant.

- Added `TASK_REMOVED` and `DESTROYED_WHILE_PLAYING` / `DESTROYED_NOT_PLAYING` events on `MediaSessionService`, and a final resumable checkpoint before normal service teardown. Abrupt OS process kills cannot reliably run `onDestroy`, so the absence of the event is not definitive.
- The Activity observes `ON_RESUME` and asks the ViewModel to recover **only** if it still has an active playback screen and source, its playback intent is true, and **both** `BackgroundPlaybackService.active` and `playerFlow` are absent. It waits 600 ms for an in-flight bind before doing a **single** re-open of the current episode through the existing source API. Intentional pauses, detail pages and explicit player close do not trigger recovery.
- This is a foreground repair, **not** guaranteed autonomous process-death continuation. A force-stop, restricted background execution, hardware decoder crash or Android/OxygenOS process kill may require a fresh activity/process initialization. Persistent full-session reconstruction across process death is a separate feature and requires robust saved-state/authorization design.
- On-device test matrix: play 2+ min locked; screen off + audio focus interruptions; task removed; app returns with same process vs cold start; verify no duplicate media session, no repeated restart after deliberate pause, and exported events after service destruction. Confirm Debug build before claiming success.
