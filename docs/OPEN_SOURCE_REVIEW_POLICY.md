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

## Notification and autoplay regression follow-up (historical; ordinary notification later removed)

Observed on a device: a media player appeared in the system media area, but not as a separate ordinary drawer notification; media switching and continuous playback were unreliable.

- Earlier versions added a separate `NotificationCompat` progress card and a Settings > 后台播放 permission request. **This entire duplicate notification flow has now been removed.** The only playback notification is Media3's system-managed foreground media notification; its native seekable timeline is managed by Android.
- The Media3 custom previous/next buttons retain explicit BACK/FORWARD slots and availability. Their commands target the existing media session; there are no parallel ordinary notification `PendingIntent` actions.
- Real background audio may advance without any visible video surface. `confirmedBackgroundPlayback` treats three seconds of progressing ExoPlayer audio as validated playback for history/checkpoint and next-media prefetch, without falsely recording a rendered video frame. A return to the foreground still receives genuine `onRenderedFirstFrame`.
- Swiping past the fetched reel queue triggers a page load **without immediately pausing the current stream**. When the new drama arrives, the existing ViewModel transition loads its media into the service. Normal episode transitions use the same single service player.
- Sanitized diagnostic events include `BACKGROUND/SERVICE_CREATED`, `SOURCE_CONFIGURED`, `MEDIA_PREVIOUS`, `MEDIA_NEXT`, and `MEDIA_ENDED`. Obsolete ordinary-notification permission/block diagnostics were removed with that implementation. Diagnostics never contain media URLs or drama titles.
- Validate foreground-service notification behavior, OPPO/OnePlus-specific notification settings, screen-off continuity, first episode/last episode, last reel awaiting discovery, audio focus, and real seekbar behavior on a phone. These changes were not confirmed by a successful current CI build in this review.

## 2026-11-08 next-episode stall and 1-second clock correction (historical)

- Checked the actual `BackgroundPlaybackService.configure()`, `Player.STATE_ENDED` callback and `DramaViewModel.closeNativeVideo()` handoff against the Media3 service-owned-player model.
- `DramaViewModel` now detaches its pointer instead of closing an encrypted `RemoteVideo` still owned by `BackgroundPlaybackService`. The service closes the prior source **after** it installs and starts the replacement (or on explicit stop).
- The Media3 `STATE_ENDED` callback schedules navigation on the next main-loop turn to avoid reentrant `stop()/setMediaSource()` while ExoPlayer dispatches listeners; it skips stale transitions when the media key has already changed.
- Explicit autoplay is armed before `prepare()`, followed by `play()`; a bounded `STATE_READY` recovery attempts to restore playback if the expected autoplay intent was lost. Playback events record `NEXT_SOURCE_PLAY_REQUESTED`, `AUTO_START_RECOVERY`, and `PLAYING`.
- Do not checkpoint the old stream after it has reached `STATE_ENDED`; that would recreate a completed-episode resume point after `finishEpisode` cleared it.
- The former ordinary notification refreshed its progress once per second. **That workaround is now removed**. The current system Media3 media control owns its own timeline clock, and Android/OxygenOS determines when to redraw it.
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

## 2026-11-08 08:49:51 background service foreground-registration finding

Observed trace:
- 08:47:33.250 UTC: `READY_AUDIO_FOCUS_LOST`; audio explicitly resumed 08:47:44.316 via `PLAYING`.
- 08:49:12.713 UTC: `DESTROYED_WHILE_PLAYING` — media service destroyed while its ExoPlayer was reporting playback active. There was no preceding `STOP_*` or `TASK_REMOVED` event in this trace.
- 08:49:35.835 UTC: `FOREGROUND_SERVICE_MISSING`; 08:49:36.436 `FOREGROUND_RECONNECT`; by 08:49:41.374 `PLAYING` resumed in a newly created session.
- 08:49:45.759 UTC: `STOP_CLOSE_PLAYER`, after the reconnect, is an explicit close and **not** the cause of the original 08:49:12 destroy.

Root cause candidate found by comparing source to **AndroidX Media3 1.8.0** `MediaSessionService.addSession` source: this app constructs the `MediaSession` in `onCreate` but does not create a bound `MediaController`; therefore `onGetSession` is not necessarily invoked and the service may never register the session with its own notification manager, even though the Android media carousel shows the session. Without a registered media session, Media3 cannot reliably promote ongoing playback to a media foreground service.

Fix: after `MediaSession.Builder(...).build()`, explicitly call `addSession(session)` **once**, before playback begins. Let the default `onUpdateNotification` implementation manage foreground promotion. Add diagnostic events for foreground requested/active transitions and verify `isSessionAdded` / `isPlaybackOngoing` during teardown. This avoids indefinite restart loops or a competing manually managed foreground notification. A session returned by `onGetSession` remains safe because Media3 `addSession` treats re-registering the same object as idempotent.

Validation required: After starting playback, exported log must show `MEDIA_SESSION_REGISTERED`, `FOREGROUND_REQUESTED` and `FOREGROUND_ACTIVE`; test 5+ minutes in background/locked state without `DESTROYED_WHILE_PLAYING`. If service still dies with `DESTROYED_FOREGROUND_ACTIVE`, inspect Android process/FGS policy and OxygenOS battery management rather than assuming the registration fix is sufficient. CI and device result unverified.

## Single system media control only (2026-11-08)

Based on the successful on-device test after explicit `MediaSessionService.addSession(session)`, the user requested removal of the redundant ordinary notification.

- `BackgroundPlaybackService.kt` no longer builds/posts a second `NotificationCompat` card, creates the `hongguo_playback_progress` channel, registers duplicate previous/play/next `PendingIntent` service actions, or refreshes a notification every second.
- The service removes the **legacy duplicate notification channel** when first created after upgrade; Media3's own foreground-service channel and `onUpdateNotification(session, startInForegroundRequired)` remain untouched.
- The `MediaSession` is still explicitly registered using `addSession(session)`, with custom BACK/FORWARD commands, session media metadata, actual ExoPlayer timeline, foreground state diagnostics, background sleep timer, stream ownership and state recovery.
- `SettingsScreen` no longer has a redundant playback-notification permission request. Android `POST_NOTIFICATIONS` remains for the **separate episode-update reminder** feature, whose permission request and toggle are retained.
- The native media progress/time display is OS-managed. The app's 1-second checkpoint ticker remains, since it also handles sleep-timer, skip-outro, background progress validation and stalled-playback detection; it no longer posts a notification.
- Only source-level consistency was checked. Debug build and locked-screen regression remain to be verified on device.

## Transient playback model empty-response on reel/detail navigation (2026-11-08 09:09)

- The user's 09:09:49 diagnostic shows `BACKGROUND/FOREGROUND_ACTIVE` and `PLAYING` for earlier videos after the system-only notification refactor. At 09:09:08, `STOP_OPEN_DETAIL` was an intentional navigation stop. The next video requested 09:09:32 failed at 09:09:35 with `MODEL_FAIL_PLAYBACK_EMPTY_RESPONSE` and `FAIL_PLAYBACK_EMPTY_RESPONSE`, **before** `SOURCE_CONFIGURED`. Do not conflate an upstream empty playback model with a Media3 foreground-service regression.
- Compared the existing adapter against upstream `tsukiyo-zero/hongguoTV` `ContentRepository` and `HomeFeedScreen` empty-response recovery guidance. The core already has bounded internal host/response retries, so the Android UI adds only **one additional cancellable retry**, after 1.5s, on the typed `EmptyPlaybackResponseException`; it does not retry HTTP 403, decoder errors, or an untyped generic network failure.
- The retry requests the **same current vid without metadataAhead**, so it does not couple a failed single-episode attempt to next-episode model prefetch. Before retry, validate the active playback generation, current episode ID, and quality limit. A user swipe, detail navigation or quality change invalidates the old request; no background retry may replace a newer video.
- Shared classification `PlaybackProblem.isEmptyPlaybackModel` with deterministic unit tests ensures that only the explicit upstream exception is eligible. Diagnostics distinguish `PRIMARY_EMPTY_RETRY`, `PRIMARY_EMPTY_RETRY_EXHAUSTED`, and the original `PLAYBACK_EMPTY_RESPONSE`; after the single retry is exhausted the UI retains an honest manual retry option.
- The registered `MediaSessionService`, native media controls, foreground lifecycle and ordinary-notification removal remain unchanged.
- These changes require a Debug CI build and on-device verification; upstream server responses can remain empty despite bounded retries.

## Official logged-in reel recommendations (2026-11-08)

The user reported that signing into their official account did not appear to affect the one-drama-per-swipe feed. Code review found two concrete gaps:
- Account recommendations are **opt-in and off by default**. A validated cloud profile alone never enables sending the saved Cookie to a feed endpoint. `AccountManager.recommendationAccount()` returns `null` until the setting is explicitly enabled, and then creates the minimal read-only `FeedAccount`.
- The main `RecommendationFeed` was already constructed with that `FeedAccount`, but the **real-only fallback category** `RecommendationFeed(category=REAL)` was created without it. Now both paths use one per-session snapshot of the account context when `officialReelsPage(restart=true)` creates the feed cursors.

A new `DramaViewModel.setAccountRecommendations()` is shared by both Settings and the account menu, instead of letting either edit the pref without invalidating the active anonymous cursor. Turning the setting on/off rebuilds the upcoming feed from an appropriately scoped cursor, while **retaining the currently playing drama and Media3 session**; homepage recommendation refreshes only when browsing the home recommendation section. The toggle has explicit explanatory copy. `OfficialReelsPolicy.retainCurrentOnAccountChange` has deterministic tests.

`REELS/ACCOUNT_REQUEST_MODE` vs `REELS/ANONYMOUS_REQUEST_MODE` confirms only whether the outgoing request is configured with an account Cookie, **not** whether the backend ranks results the same way as the official App. No cookies, device IDs, user IDs, profile data, or drama titles are logged. Server-side personalization cannot be verified here without comparing real authenticated responses. Never automatically opt in a user just because they logged in.

Relevant upstream reference: the independently maintained `tsukiyo-zero/hongguoTV` `RecommendationFeed.kt`, which attaches an opt-in account Cookie to the official distribution feed and its category requests, without behavior reporting or creating a fabricated personal recommendation model. Tests and CI need a Debug run; this edit is not a claim of device-confirmed personalization.

## 2026-11-08 09:38:58 official feed errors after account recommendation opt-in

The diagnostic shows account context was enabled at **09:37:00 UTC**, followed by `REELS/ACCOUNT_REQUEST_MODE` at 09:37:22 and 09:37:31 and generic `APP/FAIL_NETWORK_IO` failures. A later retry returned `REELS/OFFICIAL_BATCH_READY` at 09:37:46, while another account-mode request at 09:38:41 failed. Catalog/media playback remained functional. **The old log does not reveal the underlying IOException message**, so it does not prove auth rejection or a particular host/network outage.

Source review found two real recoverability/diagnostic gaps:

- The official `RecommendationFeed.page()` had a three-host pool but could throw immediately when `RequestScope.execute` raised `IOException`, without trying the next host. It now retries at most twice **for narrowly classified transient** socket timeout/connection/DNS/EOF, empty body, and HTTP 500/502/503/504 failures. All per-request session and query parameters are rebuilt for the new host; 401/403/429, changed response schemas and other deterministic errors are not retried. Cancellation handling stays with `RequestScope`.
- An opted-in account can encounter business code 110 (server access rejection). Requests now try each approved host once **with the same account identity**, then return a stable `REELS_ACCOUNT_ACCESS_REJECTED` failure; they must **never** silently substitute anonymous recommendations and label them personalized.
- `NoNewRecommendationsException` also inherits from `IOException`, so the previous generic app error misleadingly displayed “网络数据读取失败” for exhausted or duplicate-only recommendations. New `OfficialReelsFailure` classifies this as `NO_NEW`, and distinguishes account rejection, HTTP denial/rate limit/5xx, timeout, DNS, empty response, parse-format issues, and remaining IO.
- `NativeHongguoSource` records distinct sanitized `REELS/MAIN_*` and `REELS/REAL_*` root errors; `DramaViewModel` records `REELS/OFFICIAL_FAIL_*` and displays a more specific safe hint. None contain URLs, credentials, account IDs, title text, or raw upstream response. Focus remains on preserving the official logged-in feed when authorized.
- Added `OfficialReelsFailureTest` and `RecommendationTransientIoTest` to check classification and failover scope. No real authenticated network test or compiled Debug APK has yet been verified.
