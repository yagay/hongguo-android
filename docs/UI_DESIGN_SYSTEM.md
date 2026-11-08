# Hongguo UI shared design system

The shared implementation lives in `app/src/main/java/com/hongguo/rewrite/HongguoUi.kt`.

## Architecture

- `HongguoColors` owns light/dark/theme colors.
- `HongguoUi` owns component spacing, radii, icon dimensions and text sizes.
- `HongguoChoiceGroup` is the default for groups of related filter/setting options, and `HongguoChoiceChip` for a single option in a custom row.
- `HongguoToggleRow` is the common setting-switch structure. Use `dark=true` for player sheets.
- `HongguoAction` replaces simple single-label buttons. Use `PRIMARY` for a major action, `OUTLINE` for a regular action, and `QUIET` for a minor action. Retain custom buttons with progress indicators or icons.
- `HongguoIconAction` standardizes ordinary 40dp icon hit targets; video overlay circles may have their own visual sizes.
- `HongguoCard` and `HongguoNavigationRow` are the ordinary themed card and settings/list entry.

## Preserve behavior

Presentation components must not contain network, playback, login, navigation or data-loading logic. Pass actions as callbacks. Keep testTags stable, use `tagByValue` for settings that historically used a value-based identifier.

## Intentional exceptions

- Player surface, swipe gestures, seek controls, video-dependent viewport, PiP and player overlays have specialized rendering requirements.
- Drama detail's warm immersive surface and the white account drawer use dedicated palettes; share dimensions when compatible, but do not replace their backgrounds indiscriminately.
- Dialogs, text fields, loading buttons, and context menus with custom content should use centralized dimensions only where compatible.
- Do not add a parallel UI framework or duplicate shared components inside screens.
