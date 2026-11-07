package com.hongguo.rewrite

import android.app.Activity
import android.os.Bundle
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
    var mineOpen by rememberSaveable { mutableStateOf(false) }
    val darkPlayer = state.detail != null && !state.detailPageVisible &&
        state.modal != Modal.SETTINGS
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
                if (darkPlayer) HongguoColors.playerBackground
                else HongguoColors.background
            )
        ) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                BackHandler(
                    enabled = state.detail != null || state.preparingDetail ||
                        state.feedMode || state.modal != Modal.NONE || mineOpen,
                ) {
                    if (state.modal == Modal.ACCOUNT) model.closeAccount()
                    else if (state.modal != Modal.NONE) model.closeModal()
                    else if (state.detail != null || state.preparingDetail || state.feedMode) {
                        if (state.detailPageVisible) model.backFromDramaDetails()
                        else model.backFromPlayer()
                    }
                    else mineOpen = false
                }
                when {
                    state.modal == Modal.SETTINGS -> SettingsScreen(state, model)
                    state.modal == Modal.ACCOUNT -> AccountScreen(model)
                    state.detailPageVisible && state.detail != null ->
                        DramaDetailScreen(state, model)
                    state.detail != null -> key(state.detail?.drama?.id, state.feedMode) {
                        NativePlayer(state, model)
                    }
                    state.preparingDetail -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(color = HongguoColors.accent)
                                Text("正在读取剧集…")
                                TextButton(onClick = model::closePlayer) { Text("取消") }
                            }
                        }
                    }
                    else -> Scaffold(
                        containerColor = HongguoColors.background,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        bottomBar = {
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
                        },
                    ) { insets ->
                        Box(Modifier.fillMaxSize().padding(insets)) {
                            if (mineOpen) MyScreen(state, model, onBack = { mineOpen = false })
                            else CatalogScreen(state, model, onMine = { mineOpen = true })
                        }
                    }
                }
                LibrarySheets(state, model)
            }
        }
    }
}
