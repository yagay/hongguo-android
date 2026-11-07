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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
    val darkPlayer = state.detail != null && state.modal != Modal.SETTINGS
    val activity = LocalContext.current as? Activity
    SideEffect {
        activity?.let {
            val bars = WindowCompat.getInsetsController(it.window, it.window.decorView)
            bars.isAppearanceLightStatusBars = !darkPlayer
            bars.isAppearanceLightNavigationBars = !darkPlayer
        }
    }
    val palette = lightColorScheme(
        primary = HongguoColors.accent,
        onPrimary = Color.White,
        background = HongguoColors.background,
        surface = HongguoColors.surface,
        onBackground = HongguoColors.ink,
        onSurface = HongguoColors.ink,
    )
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
                        state.modal != Modal.NONE,
                ) {
                    if (state.modal != Modal.NONE) model.closeModal()
                    else model.closePlayer()
                }
                when {
                    state.modal == Modal.SETTINGS -> SettingsScreen(state, model)
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
                    else -> CatalogScreen(state, model)
                }
                LibrarySheets(state, model)
            }
        }
    }
}
