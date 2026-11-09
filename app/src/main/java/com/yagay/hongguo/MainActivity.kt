package com.yagay.hongguo

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import android.graphics.Rect
import android.view.View
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Theaters
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

private const val EXTERNAL_PIP_PREFERENCES = "external_picture_in_picture"
// Retain the old "enabled" key so auto-PiP choices survive app updates.
private const val AUTO_PIP_ENABLED = "enabled"
private const val MANUAL_PIP_BUTTON_VISIBLE = "manual_button_visible"

private fun readAutoPipEnabled(context: Context): Boolean =
    context.getSharedPreferences(EXTERNAL_PIP_PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(AUTO_PIP_ENABLED, true)

private fun readManualPipButtonVisible(context: Context): Boolean =
    context.getSharedPreferences(EXTERNAL_PIP_PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(MANUAL_PIP_BUTTON_VISIBLE, true)

class MainActivity : ComponentActivity() {
    /** PiP is a display mode for the existing Media3 session, not a second service. */
    var videoInPictureInPicture by mutableStateOf(false)
        private set
    private var autoPictureInPictureEligible = false
    private var visibleVideoForAutoPip = false
    private var openingPictureInPictureSettings = false
    // A single foreground-to-background transition may deliver both
    // onUserLeaveHint and onPause. One PiP request must cover both.
    private var autoPipEntryRequested = false
    // Source video coordinates are supplied to Android before PiP animation.
    // Without this hint, the launcher may animate the app icon/window first.
    private var pipVideoSourceView: View? = null
    private var pipSourceRect: Rect? = null
    private var pipSkipAvailability =
        BackgroundNavigationPolicy.Availability(false, false)

    /** Keep PiP controls synchronized with the selected episode or reel. */
    fun updatePipSkipAvailability(value: BackgroundNavigationPolicy.Availability) {
        if (value == pipSkipAvailability) return
        pipSkipAvailability = value
        if (videoInPictureInPicture) {
            runCatching { setPictureInPictureParams(videoPiPParams(false)) }
                .onFailure { android.util.Log.w("HongguoPiP", "PiP actions refresh rejected", it) }
        }
    }
    private val pipVideoLayoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateVideoPipSourceBounds()
        }

    fun registerVideoPipSourceView(view: View) {
        if (pipVideoSourceView === view) {
            updateVideoPipSourceBounds()
            return
        }
        pipVideoSourceView?.removeOnLayoutChangeListener(pipVideoLayoutListener)
        pipVideoSourceView = view
        view.addOnLayoutChangeListener(pipVideoLayoutListener)
        updateVideoPipSourceBounds()
    }

    fun unregisterVideoPipSourceView(view: View) {
        if (pipVideoSourceView !== view) return
        view.removeOnLayoutChangeListener(pipVideoLayoutListener)
        pipVideoSourceView = null
        // Keep the most recent valid bounds during the transition.
    }

    private fun updateVideoPipSourceBounds() {
        val view = pipVideoSourceView ?: return
        val bounds = Rect()
        if (!view.getGlobalVisibleRect(bounds) || bounds.isEmpty) return
        if (pipSourceRect != bounds) {
            pipSourceRect = bounds
            if (!videoInPictureInPicture) {
                runCatching {
                    setPictureInPictureParams(
                        videoPiPParams(autoPictureInPictureEligible))
                }
            }
        }
    }

    private fun isAutoPipAllowedNow(): Boolean =
        VideoPiPPolicy.isAutoPipAllowed(
            autoPipEnabled = readAutoPipEnabled(this),
            systemAccess = VideoPiPAccess.status(this),
        ) && !openingPictureInPictureSettings

    private fun isManualPipAllowedNow(): Boolean =
        VideoPiPPolicy.isManualEntryPermitted(VideoPiPAccess.status(this)) &&
            !openingPictureInPictureSettings

    private fun videoPiPParams(autoEnter: Boolean): PictureInPictureParams {
        // Aspect ratio is supported from API 26; auto-enter and seamless
        // resizing only exist from API 31. This app still supports API 26+.
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(
                VideoPiPPolicy.PORTRAIT_WIDTH, VideoPiPPolicy.PORTRAIT_HEIGHT))
        pipSourceRect?.let { params.setSourceRectHint(it) }
        val activePlayer = BackgroundPlaybackService.playerFlow.value
        params.setActions(PiPControls.actions(
            context = this,
            available = pipSkipAvailability,
            isPlaying = activePlayer?.isPlaying == true,
            hasPlayer = activePlayer != null,
        ))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Re-read the persisted toggle at the last moment. A stale
            // state or an OEM layout callback must never re-enable PiP
            // after the user has disabled it inside the app.
            params.setAutoEnterEnabled(autoEnter && isAutoPipAllowedNow())
            params.setSeamlessResizeEnabled(true)
        }
        return params.build()
    }

    /** Store player visibility separately from the live playback state. */
    fun updateVideoPictureInPictureEligibility(visibleVideo: Boolean) {
        visibleVideoForAutoPip = visibleVideo
        refreshAutoPictureInPictureEligibility()
    }

    /** Re-read the service player and preference immediately, even on toggle. */
    fun refreshAutoPictureInPictureEligibility() {
        val player = BackgroundPlaybackService.playerFlow.value
        autoPictureInPictureEligible =
            VideoPiPPolicy.canAutoEnterFromActivePlayer(
                isPlayerVisible = visibleVideoForAutoPip,
                actualMediaId = player?.currentMediaItem?.mediaId,
                isPlaying = player?.isPlaying == true,
            ) && isAutoPipAllowedNow()
        // In PiP, action icons must update after play/pause and skip as
        // well; the old !videoInPiP gate left disabled icons stranded.
        runCatching {
            setPictureInPictureParams(
                videoPiPParams(if (videoInPictureInPicture) false
                    else autoPictureInPictureEligible))
        }.onFailure {
            android.util.Log.w("HongguoPiP", "Unable to apply PiP params", it)
        }
    }

    fun openVideoPictureInPictureSettings(): Boolean {
        openingPictureInPictureSettings = true
        if (!videoInPictureInPicture) {
            runCatching { setPictureInPictureParams(videoPiPParams(false)) }
        }
        val packageUri = Uri.fromParts("package", packageName, null)
        // The direct Android PiP settings action is not a public SDK
        // constant. Use its documented platform action, then app settings
        // as a safe OEM fallback if the direct activity is unavailable.
        val candidates = listOf(
            Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", packageUri),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
        )
        val launched = candidates.any { intent ->
            runCatching { startActivity(intent) }.isSuccess
        }
        if (!launched) {
            openingPictureInPictureSettings = false
            updateVideoPictureInPictureEligibility(visibleVideoForAutoPip)
        }
        return launched
    }

    override fun onResume() {
        super.onResume()
        autoPipEntryRequested = false
        if (openingPictureInPictureSettings) {
            openingPictureInPictureSettings = false
            updateVideoPictureInPictureEligibility(visibleVideoForAutoPip)
        } else {
            // The player may still be running after returning from another
            // application, so re-arm system auto-PiP on every foreground entry.
            refreshAutoPictureInPictureEligibility()
        }
    }

    /** Explicit PiP action in both the full and in-app mini players. */
    fun enterVideoPictureInPicture(expectedVid: String): Boolean {
        // Manual entry is independent of auto PiP. Button visibility is
        // controlled separately by the user's manual-button preference.
        if (!isManualPipAllowedNow()) return false
        val player = BackgroundPlaybackService.playerFlow.value ?: return false
        if (expectedVid.isBlank() ||
            player.currentMediaItem?.mediaId != expectedVid) return false
        return runCatching {
            enterPictureInPictureMode(videoPiPParams(autoEnter = false))
        }.getOrDefault(false)
    }

    /**
     * All user-driven background transitions share the same PiP policy.
     * The platform can skip onUserLeaveHint when another app is launched
     * from Recents, notifications, a link or an OEM switching gesture.
     */
    private fun tryEnterAutoPipWhenLeaving(trigger: String) {
        val player = BackgroundPlaybackService.playerFlow.value
        val systemAccess = VideoPiPAccess.status(this)
        val canEnter = VideoPiPPolicy.canEnterWhenLeavingApp(
            autoPipEnabled = readAutoPipEnabled(this),
            systemAccess = systemAccess,
            isPlayerVisible = visibleVideoForAutoPip,
            actualMediaId = player?.currentMediaItem?.mediaId,
            isPlaying = player?.isPlaying == true,
            alreadyInPip = videoInPictureInPicture || isInPictureInPictureMode,
            entryRequested = autoPipEntryRequested,
            openingSettings = openingPictureInPictureSettings,
            finishingOrRecreating = isFinishing || isChangingConfigurations,
        )
        if (!canEnter) {
            android.util.Log.d(
                "HongguoPiP",
                "$trigger skipped: enabled=${readAutoPipEnabled(this)}" +
                    " access=$systemAccess visible=$visibleVideoForAutoPip" +
                    " playing=${player?.isPlaying} media=${player?.currentMediaItem?.mediaId}" +
                    " pip=$videoInPictureInPicture requested=$autoPipEntryRequested",
            )
            return
        }
        // A manual request is necessary only when the OS has not already
        // moved us into PiP. Keep the same Media3 session and source bounds.
        val entered = runCatching {
            enterPictureInPictureMode(videoPiPParams(autoEnter = false))
        }.onFailure {
            android.util.Log.w("HongguoPiP", "$trigger auto PiP request failed", it)
        }.getOrDefault(false)
        if (entered) autoPipEntryRequested = true
        else android.util.Log.w("HongguoPiP", "$trigger auto PiP was rejected")
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        tryEnterAutoPipWhenLeaving("onUserLeaveHint")
    }

    override fun onPause() {
        // Some external app switches bypass onUserLeaveHint entirely.
        // Try before super.onPause(), while this Activity is still able to
        // enter PiP; a successful earlier request prevents duplicates.
        tryEnterAutoPipWhenLeaving("onPause")
        super.onPause()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        videoInPictureInPicture = isInPictureInPictureMode
        if (isInPictureInPictureMode) refreshAutoPictureInPictureEligibility()
        // The next foreground visit is a new transition.
        if (!isInPictureInPictureMode) autoPipEntryRequested = false
    }

    override fun onDestroy() {
        // Dismissing the PiP window is an explicit close. Expanding it first
        // clears the flag so normal return to full screen does not stop audio.
        if (isFinishing && videoInPictureInPicture)
            BackgroundPlaybackService.stopActive("PIP_CLOSED")
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashCapture.install(applicationContext)
        super.onCreate(savedInstanceState)
        videoInPictureInPicture = isInPictureInPictureMode
        // Draw the app background/video under both system bars.  Keep
        // interactive controls inside safeDrawing in the Compose root.
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ otherwise adds a grey scrim, especially over the
            // three-button navigation bar even with transparent bar colors.
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        setContent { HongguoApp() }
    }
}

/**
 * Thin app shell: window insets are owned by this root exactly once.
 * Catalog, player, library and settings are independent UI modules.
 */
@Composable
private fun HongguoApp(model: DramaViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val appContext = LocalContext.current
    var autoPipEnabled by remember(appContext) {
        mutableStateOf(readAutoPipEnabled(appContext))
    }
    var manualPipButtonVisible by remember(appContext) {
        mutableStateOf(readManualPipButtonVisible(appContext))
    }
    val setManualPipButtonVisible: (Boolean) -> Unit = { visible ->
        manualPipButtonVisible = visible
        appContext.getSharedPreferences(
            EXTERNAL_PIP_PREFERENCES, Context.MODE_PRIVATE
        ).edit().putBoolean(MANUAL_PIP_BUTTON_VISIBLE, visible).apply()
    }
    var pipSystemStatus by remember(appContext) {
        mutableStateOf(VideoPiPAccess.status(appContext))
    }
    val setAutoPipEnabled: (Boolean) -> Unit = { enabled ->
        pipSystemStatus = VideoPiPAccess.status(appContext)
        if (enabled && pipSystemStatus == VideoPiPAccess.Status.UNSUPPORTED) {
            Toast.makeText(appContext, "此设备不支持系统画中画", Toast.LENGTH_SHORT).show()
        } else {
            autoPipEnabled = enabled
            appContext.getSharedPreferences(
                EXTERNAL_PIP_PREFERENCES, Context.MODE_PRIVATE
            ).edit().putBoolean(AUTO_PIP_ENABLED, enabled).apply()
            // Apply both ON and OFF immediately without waiting for Compose.
            (appContext as? MainActivity)
                ?.refreshAutoPictureInPictureEligibility()
            if (enabled && pipSystemStatus == VideoPiPAccess.Status.BLOCKED) {
                (appContext as? MainActivity)?.openVideoPictureInPictureSettings()
            }
        }
    }
    val livePlayer by BackgroundPlaybackService.playerFlow.collectAsStateWithLifecycle()
    var liveVideoIsPlaying by remember(livePlayer) {
        mutableStateOf(livePlayer?.isPlaying == true)
    }
    var liveVideoMediaId by remember(livePlayer) {
        mutableStateOf(livePlayer?.currentMediaItem?.mediaId)
    }
    DisposableEffect(livePlayer) {
        val observed = livePlayer
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                liveVideoIsPlaying = isPlaying
                (appContext as? MainActivity)?.refreshAutoPictureInPictureEligibility()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                liveVideoMediaId = mediaItem?.mediaId
                (appContext as? MainActivity)?.refreshAutoPictureInPictureEligibility()
            }
        }
        observed?.addListener(listener)
        onDispose { observed?.removeListener(listener) }
    }
    val mainActivity = LocalContext.current as? MainActivity
    val systemPictureInPicture = mainActivity?.videoInPictureInPicture == true
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                model.recoverPlaybackAfterServiceLoss()
                pipSystemStatus = VideoPiPAccess.status(appContext)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var primaryTab by rememberSaveable { mutableStateOf("home") }
    var searchLandingOpen by rememberSaveable { mutableStateOf(false) }
    var confirmExitFeed by rememberSaveable { mutableStateOf(false) }
    // A tag search opened from any primary tab belongs to the theater.
    LaunchedEffect(state.section) {
        if (state.section == Section.SEARCH) primaryTab = "theater"
    }
    val activeRoute = state.navigation.destination
    val reelModeSlide = remember { ReelModeSlideState() }
    val reelSlideScope = rememberCoroutineScope()
    fun returnFromSeries() {
        if (ReelReturnPolicy.canRestore(state)) {
            reelSlideScope.launch {
                reelModeSlide.exitRightThen { model.backFromPlayer() }
            }
        } else {
            model.backFromPlayer()
        }
    }
    val videoLayer = AppShellNavigationPolicy.videoLayer(state)
    val playerDetailsOverlay =
        videoLayer == AppShellNavigationPolicy.VideoLayer.BEHIND_DETAILS
    val darkPlayer =
        videoLayer == AppShellNavigationPolicy.VideoLayer.FULL &&
            state.modal == Modal.NONE
    // Startup/loading uses the same dark chrome as the feed player so the
    // status-bar background and icon contrast do not flash on refresh.
    val darkSystemChrome = darkPlayer || (
        state.feedMode && activeRoute == PlaybackDestination.FEED &&
            videoLayer == AppShellNavigationPolicy.VideoLayer.NONE &&
            state.modal == Modal.NONE && primaryTab == "home"
    )
    // New series opened from Theater or My: select Home to represent the
    // full-player destination. An asynchronously completed swipe feed while
    // browsing elsewhere is minimized instead of stealing the foreground.
    LaunchedEffect(activeRoute, state.detail?.drama?.id, state.miniPlayerVisible) {
        when {
            AppShellNavigationPolicy.shouldSelectHomeForNewSeries(state) ->
                primaryTab = "home"
            activeRoute == PlaybackDestination.FEED && primaryTab != "home" &&
                videoLayer == AppShellNavigationPolicy.VideoLayer.FULL ->
                model.minimizePlayer()
        }
    }
    // A details page opened over full-screen playback must not attach a
    // second PlayerView to the same ExoPlayer (which would steal its Surface).
    // Explicit catalog details keep the existing mini player.
    val showMini = videoLayer == AppShellNavigationPolicy.VideoLayer.MINI &&
        !playerDetailsOverlay
    // Both recommendation reels and the episode player reserve the same
    // compact navigation strip. Hiding it in SERIES changed the available
    // viewport height after entering a drama, invalidating the saved
    // uncropped portrait calibration and creating letterboxing/jumps.
    val showPrimaryNavigation =
        state.modal == Modal.NONE && !searchLandingOpen &&
            !playerDetailsOverlay && activeRoute != PlaybackDestination.DETAILS

    fun selectPrimaryTab(tab: String) {
        // During the first frame of an external series launch the selected
        // tab can still be My/Theater. A same-tab tap must still minimize the
        // new full player instead of being discarded.
        if (tab == primaryTab &&
            !AppShellNavigationPolicy.shouldMinimizeForTab(state, tab)) return
        if (tab == "home") {
            primaryTab = tab
            if (AppShellNavigationPolicy.shouldRestoreForHome(state)) {
                // Preserve the active Media3 item, its progress and feed queue.
                if (state.miniPlayerVisible) model.restorePlayer()
            } else {
                model.startFeed()
            }
        } else {
            // Apply the player transition BEFORE changing the selected tab.
            // The tab no longer controls whether the full player is rendered.
            if (AppShellNavigationPolicy.shouldMinimizeForTab(state, tab)) {
                model.minimizePlayer()
            }
            primaryTab = tab
            if (tab == "theater" && state.section == Section.RECOMMEND)
                model.section(Section.BROWSE)
        }
    }
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (state.themeMode) {
        "dark" -> true
        "system" -> systemDark
        else -> false
    }
    val activity = LocalContext.current as? Activity
    PlaybackScreenBrightness(
        activity = activity,
        enabled = darkPlayer && !systemPictureInPicture &&
            state.detail != null && !state.miniPlayerVisible,
        percent = state.videoBrightnessPercent,
        onDiagnostic = model::recordDisplayState,
    )
    LaunchedEffect(activity, darkPlayer) {
        if (darkPlayer && activity != null) {
            val display = if (Build.VERSION.SDK_INT >= 30) activity.display
                else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
            val types = display?.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
            model.recordDisplayHdrSupport(
                hdr10 = android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 in types,
                hlg = android.view.Display.HdrCapabilities.HDR_TYPE_HLG in types,
                dolbyVision = android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types,
            )
        }
    }
    SideEffect {
        // Avoid stale ViewModel playbackUrl/episode checks: Media3 can be
        // playing while these values are temporarily absent during a reel.
        val pipDetail = state.detail
        val pipEarlier = state.feedItems.take(state.feedIndex)
            .any { it.id !in state.hiddenRecommendationIds }
        val pipLater = state.feedItems.drop(state.feedIndex + 1)
            .any { it.id !in state.hiddenRecommendationIds }
        mainActivity?.updatePipSkipAvailability(
            BackgroundNavigationPolicy.availability(
                feedMode = state.feedMode,
                selectedIndex = state.episodeIndex,
                episodeCount = pipDetail?.episodes?.size ?: 0,
                previousFeedExists = pipEarlier,
                nextFeedAvailableOrPending =
                    pipLater || state.feedHasMore || state.feedLoading,
                allowFollowingDramaAtEnd = state.offlinePlaybackUri == null,
            )
        )
        mainActivity?.updateVideoPictureInPictureEligibility(
            darkPlayer || showMini
        )
        HongguoColors.applyTheme(state.themeStyle, state.themeMode, systemDark)
        activity?.let {
            val bars = WindowCompat.getInsetsController(it.window, it.window.decorView)
            // Full-screen search and details use light/dark palettes, not
            // permanently dark surfaces; system-bar icon contrast must follow.
            bars.isAppearanceLightStatusBars = !darkSystemChrome && !darkTheme
            bars.isAppearanceLightNavigationBars = !darkSystemChrome && !darkTheme
        }
    }
    val palette = (if (darkTheme) darkColorScheme(
        primary = HongguoColors.accent,
        onPrimary = Color.White,
        background = HongguoColors.background,
        surface = HongguoColors.surface,
        onBackground = HongguoColors.ink,
        onSurface = HongguoColors.ink,
    ) else lightColorScheme(
        primary = HongguoColors.accent,
        onPrimary = Color.White,
        background = HongguoColors.background,
        surface = HongguoColors.surface,
        onBackground = HongguoColors.ink,
        onSurface = HongguoColors.ink,
    ))
    MaterialTheme(colorScheme = palette) {
        if (systemPictureInPicture) {
            PictureInPictureVideo(state, model)
        } else Box(
            Modifier.fillMaxSize().background(
                if (darkPlayer) HongguoColors.playerBackground
                else HongguoColors.background
            )
        ) {
            // Paint the real system-bar areas with the adjoining app chrome.
            // Content owns only the *horizontal* safe insets; top and bottom
            // are separately measured to avoid double padding in 3-button
            // navigation, gesture navigation and display-cutout modes.
            Column(Modifier.fillMaxSize().imePadding()) {
                Spacer(Modifier.fillMaxWidth()
                    .background(
                        when {
                            darkSystemChrome -> HongguoColors.playerBackground
                            // "My" is drawn directly on the page background,
                            // while catalog and Settings have a surface header.
                            primaryTab == "mine" && state.modal == Modal.NONE &&
                                activeRoute == PlaybackDestination.CATALOG ->
                                HongguoColors.background
                            else -> HongguoColors.surface
                        }
                    )
                    .windowInsetsTopHeight(WindowInsets.safeDrawing))
                Column(Modifier.weight(1f).fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                    )) {
                    Box(Modifier.weight(1f)) {
                        BackHandler(
                            enabled = state.modal != Modal.NONE ||
                                activeRoute != PlaybackDestination.CATALOG ||
                                (activeRoute == PlaybackDestination.CATALOG &&
                                    state.navigation.origin == PlaybackOrigin.SWIPE_FEED) ||
                                primaryTab != "home" || searchLandingOpen,
                        ) {
                            when {
                                state.modal == Modal.ACCOUNT -> model.closeAccount()
                                state.modal != Modal.NONE -> model.closeModal()
                                activeRoute == PlaybackDestination.DETAILS ->
                                    model.backFromDramaDetails()
                                searchLandingOpen -> searchLandingOpen = false
                                primaryTab != "home" ->
                                    selectPrimaryTab("home")
                                activeRoute == PlaybackDestination.FEED ->
                                    confirmExitFeed = true
                                activeRoute == PlaybackDestination.SERIES ->
                                    returnFromSeries()
                                activeRoute == PlaybackDestination.CATALOG &&
                                    state.navigation.origin == PlaybackOrigin.SWIPE_FEED -> {
                                    if (state.detail != null) model.restorePlayer()
                                    else model.startFeed()
                                }
                            }
                        }
                    Box(Modifier.fillMaxSize()) {
                    when {
                        state.modal == Modal.SETTINGS -> SettingsScreen(state, model)
                        state.modal == Modal.ACCOUNT -> AccountScreen(model)
                        activeRoute == PlaybackDestination.DETAILS &&
                            state.detail != null && !playerDetailsOverlay ->
                            // Details opened from the old catalog slide over the
                            // catalog, preserving its already-visible mini player.
                            if (primaryTab == "mine") MyScreen(state, model, onBack = { selectPrimaryTab("home") })
                            else CatalogScreen(state, model, onMine = { selectPrimaryTab("mine") },
                                onSearchOpen = { searchLandingOpen = true })
                        videoLayer in setOf(
                            AppShellNavigationPolicy.VideoLayer.FULL,
                            AppShellNavigationPolicy.VideoLayer.BEHIND_DETAILS,
                        ) && state.detail != null ->
                            // UI mode changes do not destroy the active Media3 item.
                            key(state.detail?.drama?.id) {
                                NativePlayer(state, model,
                                    modeSlide = reelModeSlide,
                                    onExitFeed = { confirmExitFeed = true },
                                    onBackFromSeries = ::returnFromSeries,
                                    onEnterSeries = { episodeIndex ->
                                        val dramaId = state.detail?.drama?.id
                                        if (state.feedMode && dramaId != null) {
                                            reelSlideScope.launch {
                                                // Capture one frame of the outgoing reel
                                                // before changing routes. The foreground
                                                // remains the only live Media3 player.
                                                val ready = reelModeSlide
                                                    .captureThenPrepareEntry(dramaId)
                                                if (ready) {
                                                    val latest = model.state.value
                                                    if (latest.feedMode &&
                                                        latest.detail?.drama?.id == dramaId) {
                                                        model.exitFeedToEpisode(episodeIndex)
                                                    } else {
                                                        reelModeSlide.cancelPreparedEntry()
                                                    }
                                                }
                                            }
                                        } else {
                                            model.exitFeedToEpisode(episodeIndex)
                                        }
                                    },
                                    autoPipEnabled = autoPipEnabled,
                                    manualPipButtonVisible = manualPipButtonVisible,
                                    pipSystemStatus = pipSystemStatus,
                                    onAutoPipEnabled = setAutoPipEnabled,
                                    onManualPipButtonVisible = setManualPipButtonVisible)
                            }
                        AppShellNavigationPolicy.shouldShowLoading(state, primaryTab) &&
                            !searchLandingOpen -> {
                            val upcoming = if (state.feedMode)
                                state.feedItems.getOrNull(state.feedIndex) else null
                            ReelLoadingScreen(
                                state = state,
                                model = model,
                                onSearch = { searchLandingOpen = true },
                                drama = upcoming,
                                coverUrl = upcoming?.cover?.takeIf(String::isNotBlank)
                                    ?.let(model::cover),
                                loading = state.feedLoading || state.preparingDetail,
                                preparingSeries = state.preparingDetail,
                                error = state.playbackError ?: state.feedError,
                                onRetry = {
                                    when {
                                        state.feedMode && upcoming != null ->
                                            model.open(upcoming, resumeEp = 1, feed = true)
                                        state.feedMode -> model.retryOfficialReels()
                                        state.navigation.origin == PlaybackOrigin.SWIPE_FEED ->
                                            model.startFeed()
                                        else -> model.closePlayer()
                                    }
                                },
                                onExit = {
                                    if (state.feedMode) confirmExitFeed = true
                                    else model.backFromPlayer()
                                },
                                exitLabel = if (state.feedMode) "退出刷短剧"
                                    else "返回",
                            )
                        }
                        searchLandingOpen -> SearchLandingScreen(
                            state, model,
                            onClose = { searchLandingOpen = false },
                            onResults = { searchLandingOpen = false; primaryTab = "theater" },
                        )
                        else -> Box(Modifier.fillMaxSize()) {
                            if (primaryTab == "mine") MyScreen(state, model,
                                onBack = { selectPrimaryTab("home") })
                            else CatalogScreen(state, model,
                                onMine = { selectPrimaryTab("mine") },
                                onSearchOpen = { searchLandingOpen = true })
                        }
                        }
                        // Keep the player below the animated detail route. Closing
                        // the page slides it back to the right without disposing the
                        // playing video, reloading Media3, or showing the old Home.
                        if (state.detail != null) {
                            RightSlidePage(
                                visible = activeRoute == PlaybackDestination.DETAILS &&
                                    state.modal == Modal.NONE,
                                tag = "drama_detail_slide_page",
                            ) {
                                DramaDetailScreen(state, model)
                            }
                        }
                    }
                        LibrarySheets(state, model)
                        // Floating video-only window: overlay the page rather than
                        // consuming an entire row above the bottom navigation.
                        // Its fixed slot stays stable across catalog/My/settings.
                        if (showMini) InAppMiniPlayer(
                            state = state,
                            model = model,
                            modifier = Modifier.align(Alignment.BottomEnd)
                                .padding(end = 12.dp, bottom = 12.dp),
                            onExpand = {
                                primaryTab = "home"
                                searchLandingOpen = false
                                model.restorePlayer()
                            },
                        )
                    }
                    if (showPrimaryNavigation) {
                        HongguoPrimaryNavigation(
                            selectedTab = primaryTab,
                            onSelect = ::selectPrimaryTab,
                        )
                    }
                }
                // Unlike the old safeDrawingPadding(), the bottom system
                // navigation area now uses the SAME background as the app's
                // navigation bar instead of the catalog/body background.
                Spacer(Modifier.fillMaxWidth()
                    .background(
                        when {
                            showPrimaryNavigation -> HongguoColors.surface
                            darkPlayer -> HongguoColors.playerBackground
                            else -> HongguoColors.background
                        }
                    )
                    .windowInsetsBottomHeight(WindowInsets.safeDrawing))
            }
        }
        if (confirmExitFeed) {
            AlertDialog(
                onDismissRequest = { confirmExitFeed = false },
                title = { Text("退出刷短剧？") },
                text = { Text("退出应用后将停止当前播放。") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmExitFeed = false
                        model.closePlayer()
                        activity?.finish()
                    }) { Text("退出") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmExitFeed = false }) { Text("继续观看") }
                },
            )
        }
    }
}


/** Render only the video surface in system PiP, never Compose buttons or sheets. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PictureInPictureVideo(state: AppState, model: DramaViewModel) {
    val player by BackgroundPlaybackService.playerFlow.collectAsStateWithLifecycle()
    val detail = state.detail
    val expectedVid = detail?.episodes?.getOrNull(state.episodeIndex)?.vid
    // Preserve the decoded frame while the same item buffers. A mismatched
    // item still uses artwork so PiP never shows the previous episode as new.
    val showCover = PlaybackFramePolicy.showCover(expectedVid,
        player?.currentMediaItem?.mediaId)
    Box(Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center) {
        // Keep the same PlayerView throughout PiP next-episode transitions:
        // a temporary media ID mismatch must not destroy its video surface.
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setKeepContentOnPlayerReset(true)
                    this.player = player
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (showCover && detail?.drama?.cover?.isNotBlank() == true) {
            AsyncImage(
                model = model.cover(detail.drama.cover),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
