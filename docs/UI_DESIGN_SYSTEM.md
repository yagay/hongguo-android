# Hongguo UI design system

All ordinary Android pages share one design system. The implementation lives in
`app/src/main/java/com/yagay/hongguo/HongguoUi.kt`; dynamic theme tokens
live in `AppDesign.kt`. Do not create parallel screen-local theme palettes.

## Consolidated shared components

- `HongguoPreferenceUi.kt` owns all settings groups, navigation/value rows,
  toggle forwarding, dividers and selection sheets. Do not recreate compact
  setting components inside individual screens.
- Account and Library destructive confirmations share `HongguoConfirmDialog`;
  all existing poster/account/library context menu surfaces share `HongguoPopupMenu`.
  Input dialogs retain their specialized editable text content.
- Theater category navigation is in `HongguoCategoryNavigation`, and player
  settings now call the shared headings, chips and toggles directly.
- These changes unify components first; the Material 3 alternative can use
  them without duplicating page state or changing classic player geometry.
- Keep the original Compose testTag strings during migration and never relocate
  Media3 playback state into appearance components.

## UI style switch (rewrite)

- Settings → Appearance → UI style: `classic` (default, original presentation)
  or `material3` (new alternative layer). The preference key is `ui_style`.
- Both styles use the exact same screen routes, view model, Media3 player, repository,
  user account, data storage, downloaded content, comment flows and history.
- Android 12+ Material 3 follows dynamic system colors; earlier devices use
  a warm Material 3 fallback palette. Display mode still handles light/dark/system.
- Regular Material 3 chrome uses navigation items with labels, tonal surfaces,
  24dp cards, Material-shaped controls, standard switches and a 28dp modal sheet.
- The existing compact navigation within an immersive video uses the classic 40dp
  geometry to prevent cropping/gesture regressions. Video surface, PiP, overlays,
  subtitles and gesture regions never become separate player implementations.
- Switch back to Classic at any time; stored theme presets remain unchanged.
- Do not key player state or ViewModel identity to `uiStyle`; preserve active media
  and test tags across switching.


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
| Standard settings switch row | `HongguoToggleRow` / `HongguoSettingsToggle` |
| Settings groups, rows, dividers | `HongguoSettingsSection`, `HongguoSettingsRow`, `HongguoSettingsDivider` |
| Reusable single choice bottom sheets | `HongguoSingleChoiceSheet` |
| Contextual menus | `HongguoPopupMenu` / `HongguoActionMenu` |
| Confirmation dialogs | `HongguoConfirmDialog` |
| Theater category navigation | `HongguoCategoryNavigation` |
| Video settings headings | `HongguoPlayerSectionTitle` |
| Primary/outline/quiet actions | `HongguoAction` |
| Normal icon action | `HongguoIconAction` |
| Video top circular action | `HongguoPlayerTopAction` |
| History, collections, likes and reservations tabs | `HongguoTabs` |
| Reusable drama cover | `HongguoPosterCover` |
| Empty list and search results | `HongguoEmptyState` |
| Library, filter and video modal title | `HongguoSheetHeader` |
| All bottom sheets | `HongguoModalSheet` (player alias `HongguoPlayerSheet`) |
| Player panel section layout | `HongguoPlayerPanel` |
| Comment and related-drama cards | `HongguoSheetListRow` |
| Player episode/time controls | `HongguoPlayerEpisodeEntry`, `HongguoPlayerTimeLabel` |
| Player progress/episode chips | `HongguoPlayerSeekTrack`, `HongguoEpisodeCell` |
| Home/Theater/My icon-only navigation | `HongguoPrimaryNavigation` |

## Page rules

- Ordinary page backgrounds, cards and text use the active `HongguoColors` values.
- A standard app header is 56dp high; horizontal content gutters are 16dp.
- Cards use 16dp radius; controls use 10dp radius; icon targets use 40dp.
- Selected tabs use the same 2dp indicator and text hierarchy on My and Account.
- All three bottom navigation tabs use the same 40dp layout and the active `HongguoColors.surface`/`ink`/`muted` palette; never a separate fixed-dark tab style.
- The bottom navigation has ONE appearance; do not expose unimplemented style options.
- When UI elements look alike and behave alike, move layout into a shared component.
  Keep state, API calls, loading, media playback and navigation callbacks in screens.
- Do not remove or rename existing test tags when migrating a screen.
- Theme-specific color values belong in `HongguoColors`, not screen-local constants.

## Thematic exceptions

- The video Surface, PiP window, video gestures, episode timeline, subtitles
  and playback overlay require independent sizing and transparent/black layers.
  Video-only top buttons share `HongguoPlayerTopAction`.
- Player settings, comments, episode selection, Library sheets and theater filters
  all share one themed modal shape, title hierarchy, actions and drag handle.
  Sheets follow the app's selected light/dark mode, **not** a permanently dark
  video palette. Background media continues independently underneath the sheet.
- Player bottom progress and episode/time chrome use the same themed surface as
  the primary navigation; the actual video viewport and gesture overlays may
  remain dark or transparent to preserve readability against moving video.
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
