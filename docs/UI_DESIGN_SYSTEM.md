# Hongguo UI design system

All ordinary Android pages share one design system. The implementation lives in
`app/src/main/java/com/hongguo/rewrite/HongguoUi.kt`; dynamic theme tokens
live in `AppDesign.kt`. Do not create parallel screen-local theme palettes.

## Shared components

| Purpose | Component |
| --- | --- |
| App theme and theme mode | `HongguoColors` |
| Sizing, spacing, radii | `HongguoUi` |
| Ordinary page title | `HongguoPageHeader` |
| Section title | `HongguoSectionTitle` |
| Themed content background | `HongguoPageSurface` |
| Cards and account panels | `HongguoCard` |
| Settings/drawer menu row | `HongguoNavigationRow` |
| Chips and option groups | `HongguoChoiceChip`, `HongguoChoiceGroup` |
| Standard settings switch row | `HongguoToggleRow` |
| Primary/outline/quiet actions | `HongguoAction` |
| Normal icon action | `HongguoIconAction` |
| Video top circular action | `HongguoPlayerTopAction` |
| History, collections, likes and reservations tabs | `HongguoTabs` |
| Reusable drama cover | `HongguoPosterCover` |
| Empty list and search results | `HongguoEmptyState` |
| Library, filter and video modal title | `HongguoSheetHeader` |
| Home/Theater/My icon-only navigation | `HongguoPrimaryNavigation` |

## Page rules

- Ordinary page backgrounds, cards and text use the active `HongguoColors` values.
- A standard app header is 56dp high; horizontal content gutters are 16dp.
- Cards use 16dp radius; controls use 10dp radius; icon targets use 40dp.
- Selected tabs use the same 2dp indicator and text hierarchy on My and Account.
- All three bottom navigation tabs are one dark 40dp strip, including browse screens.
- The bottom navigation has ONE appearance; do not expose unimplemented style options.
- When UI elements look alike and behave alike, move layout into a shared component.
  Keep state, API calls, loading, media playback and navigation callbacks in screens.
- Do not remove or rename existing test tags when migrating a screen.
- Theme-specific color values belong in `HongguoColors`, not screen-local constants.

## Thematic exceptions

- The video Surface, PiP window, video gestures, episode timeline, subtitles
  and playback overlay require independent sizing and transparent/black layers.
  Video-only top buttons share `HongguoPlayerTopAction`.
- `PlaybackExperienceSheet` uses a dark sheet, but the shared header and choice
  components are reused, with a dark styling flag.
- `OfficialLoginActivity` uses native Android views for secure website login.
  Its *outer* chrome reads the saved app theme; the official WebView content
  and verification flow must never be modified merely to restyle it.
- Special account/cloud data remain separate from local list data. Presentation
  can be shared without merging database records or changing navigation behavior.
- Video cover overlays and poster click zones retain their original behaviors.

## Migration boundaries and verification

Regular page UI was migrated across Theater, My, Search, Detail, Account,
Settings, Discovery, Library sheets, Reels account drawer and player settings.
Debug Kotlin compilation and unit tests must both pass after UI changes.
Manual visual verification on the device remains necessary for theme contrast,
system window insets, text truncation, dynamic episode data and video PiP.
