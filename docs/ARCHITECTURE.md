# Android architecture (rewrite-web-native)

## Ownership rules

1. `BackgroundPlaybackService` is the ONLY ExoPlayer and active media-session
   owner. Recreating or detaching a Compose surface must not reload the media.
2. `ReelMediaSurface.kt` attaches the active player, binds controls to the
   selected episode ID and reports viewport dimensions to Android PiP.
3. `PlaybackSocialOverlays.kt` owns comments/danmaku UI and frame-clock
   rendering; neither owns media sources or changes player lifetime.
4. `AppShellNavigationPolicy` derives full / behind-details / mini / none
   from playback state. Navigation selection should not create a second player.
5. `CatalogRepository` is the only catalog source-selection and page-merging
   boundary. Official reels have a separate cursor and must not be mixed into
   catalog pagination.
6. `NativePlaybackResolver` performs bounded retry and caller-provided
   generation checks. Service alone installs the successful MediaItem.
7. `LocalContentStore` validates existing preference-backed saved content.
   Existing keys and JSON schemas are retained, so upgrading cannot erase
   watch-later, offline indexes, search history or reel history.
8. `LocalDownloadManager` owns SAF tasks, concurrency and one RemoteVideo
   handle per job. ViewModel only presents task state and records completed
   offline references.
9. `AccountManager` keeps authentication, profile refresh, reservations,
   likes and upload request lifecycles separate. Logging out cancels active jobs.

## Current boundaries / intentionally deferred

- The large `AppState` is still a compatibility snapshot for existing screens.
  Split it into truly independent StateFlows only after screen-by-screen
  collection and lifecycle tests; changing it all at once could disrupt Media3
  playback and restoration. Domain functionality is moving behind repositories.
- `LocalDownloadManager` is foreground-process scoped and does not claim
  background persistence or interrupted-job resume. Implement WorkManager
  resume only with persisted SAF grants, reauthorized expiring media URLs,
  checksum verification and explicit user notification behavior.
- SharedPreferences remains the on-disk representation for existing users.
  A Room migration needs upgrade/rollback tests and durable checkpoints before
  replacing the JSON history format.
- The optional HTTP backend remains supported. Do not disable cleartext in
  the global manifest without designing a per-endpoint opt-in network security
  policy and validating existing user configuration.
- Service exported status and Media3 controller permissions must be checked
  against actual Android and system media controllers before restricting
  external bindings.
- No playback/codec/registration protocol is rewritten as part of UI or
  architecture cleanup.

## Verification

GitHub Actions runs only Debug build, strict JVM unit tests (failures block
the workflow) and non-blocking lint diagnostics. Passing those tests is not
proof of correct real-device behavior. Verify the complete player journey:
My recent -> full player -> Theater -> mini -> My -> Home -> same Media3 item,
plus swipe next drama, details, system PiP, screen-off, account renewal,
video retry and SAF download cancel/retry.
