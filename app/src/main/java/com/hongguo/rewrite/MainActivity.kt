package com.hongguo.rewrite

import android.app.Activity
import android.os.Bundle
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashCapture.install(applicationContext)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME)
                model.recoverPlaybackAfterServiceLoss()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var mineOpen by rememberSaveable { mutableStateOf(false) }
    var searchLandingOpen by rememberSaveable { mutableStateOf(false) }
    // A detail tag may originate from the My tab; search results must be visible.
    LaunchedEffect(state.detail, state.section) {
        if (state.detail == null && state.section == Section.SEARCH) mineOpen = false
    }
    val detailImmersive = state.detailPageVisible && state.detail != null &&
        state.modal == Modal.NONE
    val darkPlayer = state.detail != null && !state.detailPageVisible &&
        !state.miniPlayerVisible && state.modal != Modal.SETTINGS
    val showMini = state.miniPlayerVisible && state.detail != null &&
        !state.preparingDetail
    val miniInCatalog = showMini && state.modal != Modal.SETTINGS &&
        state.modal != Modal.ACCOUNT && !state.detailPageVisible &&
        !searchLandingOpen
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (state.themeMode) {
        "dark" -> true
        "system" -> systemDark
        else -> false
    }
    val activity = LocalContext.current as? Activity
    SideEffect {
        HongguoColors.applyTheme(state.themeStyle, state.themeMode, systemDark)
        activity?.let {
            val bars = WindowCompat.getInsetsController(it.window, it.window.decorView)
            // Full-screen search and details use light/dark palettes, not
            // permanently dark surfaces; system-bar icon contrast must follow.
            bars.isAppearanceLightStatusBars = !darkPlayer && !darkTheme
            bars.isAppearanceLightNavigationBars = !darkPlayer && !darkTheme
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
        Box(
            Modifier.fillMaxSize().background(
                if (detailImmersive)
                    if (darkTheme) Color(0xFF6A4939) else Color(0xFFEED7C6)
                else if (searchLandingOpen)
                    if (darkTheme) Color(0xFF101010) else Color(0xFFFFFBF7)
                else if (darkPlayer) HongguoColors.playerBackground
                else HongguoColors.background
            )
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Box(Modifier.weight(1f)) {
                    BackHandler(
                        enabled = state.modal != Modal.NONE ||
                            state.detailPageVisible || state.preparingDetail ||
                            (!state.miniPlayerVisible && (state.detail != null || state.feedMode)) ||
                            mineOpen || searchLandingOpen,
                    ) {
                        if (state.modal == Modal.ACCOUNT) model.closeAccount()
                        else if (state.modal != Modal.NONE) model.closeModal()
                        else if (state.detailPageVisible) model.backFromDramaDetails()
                        else if (state.miniPlayerVisible) {
                            // Navigate within the catalog without stopping the
                            // mini video; root Back falls through to Android.
                            if (searchLandingOpen) searchLandingOpen = false
                            else if (mineOpen) mineOpen = false
                        } else if (state.detail != null || state.preparingDetail || state.feedMode)
                            model.backFromPlayer()
                        else if (searchLandingOpen) searchLandingOpen = false
                        else mineOpen = false
                    }
                when {
                    state.modal == Modal.SETTINGS -> SettingsScreen(state, model)
                    state.modal == Modal.ACCOUNT -> AccountScreen(model)
                    state.detailPageVisible && state.detail != null ->
                        DramaDetailScreen(state, model)
                    state.detail != null && !state.miniPlayerVisible ->
                        key(state.detail?.drama?.id, state.feedMode) {
                        NativePlayer(state, model)
                    }
                    state.preparingDetail -> {
                        val upcoming = if (state.feedMode)
                            state.feedItems.getOrNull(state.feedIndex) else null
                        Box(
                            Modifier.fillMaxSize().background(
                                if (upcoming != null) Color.Black
                                else HongguoColors.background),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (upcoming != null && upcoming.cover.isNotBlank()) {
                                AsyncImage(
                                    model = model.cover(upcoming.cover),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                Box(Modifier.fillMaxSize().background(
                                    Color.Black.copy(alpha = 0.45f)))
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (upcoming == null) {
                                    CircularProgressIndicator(color = HongguoColors.accent)
                                    Text("正在读取剧集…")
                                } else {
                                    Text(upcoming.title, color = Color.White)
                                    Text("正在准备播放", color = Color.White.copy(alpha = 0.84f))
                                }
                                TextButton(onClick = model::closePlayer) {
                                    Text("取消", color = if (upcoming != null)
                                        Color.White else HongguoColors.accent)
                                }
                            }
                        }
                    }
                    searchLandingOpen -> SearchLandingScreen(
                        state, model,
                        onClose = { searchLandingOpen = false },
                        onResults = { searchLandingOpen = false; mineOpen = false },
                    )
                    else -> Scaffold(
                        containerColor = HongguoColors.background,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        bottomBar = {
                            Column {
                                if (miniInCatalog) InAppMiniPlayer(state, model)
                                Surface(
                                modifier = if (state.navigationStyle == "glass")
                                    Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                else Modifier,
                                shape = RoundedCornerShape(
                                    if (state.navigationStyle == "glass") 26.dp else 0.dp),
                                color = HongguoColors.surface,
                                shadowElevation = if (state.navigationStyle == "glass") 8.dp else 0.dp,
                            ) {
                                NavigationBar(containerColor = Color.Transparent) {
                                    NavigationBarItem(selected = !mineOpen,
                                        onClick = { mineOpen = false },
                                        icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
                                        label = { Text("首页") })
                                    NavigationBarItem(selected = mineOpen,
                                        onClick = { mineOpen = true },
                                        icon = { Icon(Icons.Outlined.PersonOutline, contentDescription = null) },
                                        label = { Text("我的") })
                                }
                            }
                            }
                        },
                    ) { insets ->
                        Box(Modifier.fillMaxSize().padding(insets)) {
                            if (mineOpen) MyScreen(state, model, onBack = { mineOpen = false })
                            else CatalogScreen(state, model, onMine = { mineOpen = true },
                                onSearchOpen = { searchLandingOpen = true })
                        }
                    }
                }
                    LibrarySheets(state, model)
                }
                // Settings, account, search and drama details have no catalog
                // NavigationBar; their mini player stays fixed at the bottom.
                if (showMini && !miniInCatalog) InAppMiniPlayer(state, model)
            }
        }
    }
}
