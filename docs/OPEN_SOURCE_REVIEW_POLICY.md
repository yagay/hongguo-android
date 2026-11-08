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
