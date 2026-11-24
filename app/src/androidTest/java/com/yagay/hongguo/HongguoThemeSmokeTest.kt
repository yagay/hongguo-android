package com.yagay.hongguo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HongguoThemeSmokeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun classicLightControlsRespond() = render("classic", "light")
    @Test fun classicDarkControlsRespond() = render("classic", "dark")
    @Test fun material3LightControlsRespond() = render("material3", "light")
    @Test fun material3DarkControlsRespond() = render("material3", "dark")

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun episodeSheetKeepsGesturesWithoutUpwardEdgeBounce() {
        var open by mutableStateOf(true)
        lateinit var state: SheetState
        composeRule.setContent {
            HongguoAppTheme(previewTheme("material3", "light")) {
                state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
                if (open) HongguoEpisodePlayerSheetWithState(
                    onDismissRequest = { open = false },
                    sheetState = state,
                    modifier = Modifier.testTag("episode_sheet_smoke"),
                ) { gestures ->
                    Button(onClick = { open = false },
                        modifier = Modifier.testTag("episode_sheet_smoke_close")) {
                        Text("关闭选集")
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier.fillMaxWidth()
                            .height((LocalConfiguration.current.screenHeightDp * .64f).dp)
                            .then(gestures.grid)
                            .testTag("episode_grid_smoke"),
                        overscrollEffect = null,
                    ) {
                        items(150) { index ->
                            HongguoEpisodeCell(label = "${index + 1}",
                                selected = false, onClick = {},
                                modifier = Modifier.testTag("smoke_episode_$index"))
                        }
                    }
                }
            }
        }
        val sheet = composeRule.onNodeWithTag("episode_sheet_smoke")
        val grid = composeRule.onNodeWithTag("episode_grid_smoke")
        val handle = composeRule.onNodeWithTag("hongguo_sheet_drag_handle", useUnmergedTree = true)
        composeRule.waitForIdle()
        val initial = composeRule.runOnIdle { state.currentValue }
        val distance = composeRule.runOnIdle { state.requireOffset() * .7f }
        org.junit.Assert.assertTrue("Sheet must be visible", initial != SheetValue.Hidden)
        if (initial == SheetValue.PartiallyExpanded) {
            handle.performTouchInput {
                swipeUp(startY = center.y, endY = center.y - distance, durationMillis = 300)
            }
        }
        composeRule.runOnIdle {
            org.junit.Assert.assertEquals("Upward handle swipe expands sheet",
                SheetValue.Expanded, state.currentValue)
        }
        val expandedOffset = composeRule.runOnIdle { state.requireOffset() }

        grid.performScrollToIndex(149)
        repeat(3) {
            grid.performTouchInput { swipeUp() }
            composeRule.runOnIdle {
                org.junit.Assert.assertEquals("Upward grid edge must not move sheet",
                    expandedOffset, state.requireOffset(), 1f)
                org.junit.Assert.assertEquals(SheetValue.Expanded, state.currentValue)
            }
        }
        composeRule.onNodeWithTag("smoke_episode_149").assertExists()

        grid.performTouchInput {
            swipeDown(startY = height * .35f, endY = height * .55f, durationMillis = 600)
        }
        composeRule.runOnIdle {
            org.junit.Assert.assertEquals("Normal downward grid scroll keeps sheet expanded",
                SheetValue.Expanded, state.currentValue)
            org.junit.Assert.assertEquals(expandedOffset, state.requireOffset(), 1f)
        }

        // Drag from the handle, not from the grid or a clipped sheet node.
        handle.performTouchInput {
            swipeDown(startY = center.y, endY = center.y + distance.coerceAtLeast(300f),
                durationMillis = 300)
        }
        val stillOpen = composeRule.runOnIdle { open }
        if (stillOpen) {
            composeRule.runOnIdle {
                org.junit.Assert.assertTrue("Downward handle swipe collapses sheet",
                    state.currentValue != SheetValue.Expanded)
            }
            composeRule.onNodeWithTag("episode_sheet_smoke_close").performClick()
        }
        sheet.assertDoesNotExist()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun shortEpisodeGridAndHeaderStayStillDuringUpwardDrag() {
        var open by mutableStateOf(true)
        lateinit var state: SheetState
        composeRule.setContent {
            HongguoAppTheme(previewTheme("material3", "dark")) {
                state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                if (open) HongguoEpisodePlayerSheetWithState(
                    onDismissRequest = { open = false },
                    sheetState = state,
                    modifier = Modifier.testTag("short_sheet"),
                ) { gestures ->
                    HongguoPlayerPanel("选集", modifier = gestures.header) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(5),
                            modifier = Modifier.fillMaxWidth().height(240.dp)
                                .then(gestures.grid).testTag("short_grid"),
                            overscrollEffect = null,
                        ) {
                            items(10) { index ->
                                HongguoEpisodeCell(label = "${index + 1}",
                                    selected = false, onClick = {})
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val offset = composeRule.runOnIdle {
            org.junit.Assert.assertEquals(SheetValue.Expanded, state.currentValue)
            state.requireOffset()
        }
        val grid = composeRule.onNodeWithTag("short_grid")
        val handle = composeRule.onNodeWithTag("hongguo_sheet_drag_handle", useUnmergedTree = true)
        repeat(3) {
            // Assert before lifting the finger, not just after animations settle.
            grid.performTouchInput {
                down(center)
                moveBy(Offset(0f, -height * .35f))
            }
            composeRule.runOnIdle {
                org.junit.Assert.assertEquals("Grid must not shift under finger",
                    offset, state.requireOffset(), 1f)
            }
            grid.performTouchInput { up() }
            handle.performTouchInput {
                down(center)
                moveBy(Offset(0f, -120f))
            }
            composeRule.runOnIdle {
                org.junit.Assert.assertEquals("Handle must not overshoot expanded anchor",
                    offset, state.requireOffset(), 1f)
            }
            handle.performTouchInput { up() }
            composeRule.runOnIdle {
                org.junit.Assert.assertEquals(offset, state.requireOffset(), 1f)
            }
        }
        handle.performTouchInput {
            swipeDown(startY = center.y, endY = center.y + 300f, durationMillis = 300)
        }
        composeRule.onNodeWithTag("short_sheet").assertDoesNotExist()
    }

    @Test fun themeBackgroundChangesBetweenClassicAndMaterial() {
        var style by mutableStateOf("classic")
        composeRule.setContent {
            HongguoAppTheme(previewTheme(style, "light")) {
                Box(Modifier.fillMaxSize().background(HongguoColors.background))
            }
        }
        val classic = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val classicPixel = classic.getPixel(2, 2)
        composeRule.runOnIdle { style = "material3" }
        composeRule.waitForIdle()
        val material = composeRule.onRoot().captureToImage().asAndroidBitmap()
        org.junit.Assert.assertNotEquals(classicPixel, material.getPixel(2, 2))
        // Pixel baselines for the two immutable brand palettes.
        org.junit.Assert.assertEquals(0xFFFCF8F4.toInt(), classicPixel)
        org.junit.Assert.assertEquals(0xFFFFF8F6.toInt(), material.getPixel(2, 2))
        saveScreenshot("theme_surface_classic_light", classic)
        saveScreenshot("theme_surface_material3_light", material)
    }

    @Test fun posterSharedElementUsesOneKeyForBothEndpoints() {
        var detailVisible by mutableStateOf(false)
        composeRule.setContent {
            HongguoAppTheme(previewTheme("material3", "light")) {
                SharedTransitionLayout {
                    CompositionLocalProvider(
                        LocalPosterSharedContext provides PosterSharedContext(
                            scope = this@SharedTransitionLayout, dramaId = "poster-smoke",
                            detailsVisible = detailVisible, enabled = true,
                        ),
                    ) {
                        Column {
                            Row {
                                Box(Modifier.hongguoSharedPoster("poster-smoke", false)
                                    .size(80.dp, 120.dp).background(Color.Red)
                                    .testTag("shared_source"))
                                Box(Modifier.hongguoSharedPoster("poster-smoke", true)
                                    .size(120.dp, 180.dp).background(Color.Red)
                                    .testTag("shared_detail"))
                            }
                            Button(onClick = { detailVisible = !detailVisible }) {
                                Text("切换封面")
                            }
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("shared_source").assertExists()
        composeRule.onNodeWithTag("shared_detail").assertExists()
        composeRule.onNodeWithText("切换封面").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("shared_detail").assertExists()
        saveScreenshot("poster_shared_element", composeRule.onRoot()
            .captureToImage().asAndroidBitmap())
    }

    @Test fun materialSettingsSectionsCollapseWithoutRemovingControls() {
        composeRule.setContent {
            HongguoAppTheme(previewTheme("material3", "light")) {
                Column {
                    HongguoSettingsSection("测试分组") {
                        Text("可见的设置选项")
                    }
                }
            }
        }
        composeRule.onNodeWithText("可见的设置选项").assertExists()
        composeRule.onNodeWithTag("settings_section_toggle_测试分组").performClick()
        composeRule.onNodeWithText("可见的设置选项").assertDoesNotExist()
        composeRule.onNodeWithTag("settings_section_toggle_测试分组").performClick()
        composeRule.onNodeWithText("可见的设置选项").assertExists()
    }

    @Test fun largeFontLoginChoicesWrapWithoutLosingActions() {
        composeRule.setContent {
            val originalDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(
                density = originalDensity.density, fontScale = 1.5f,
            )) {
                HongguoAppTheme(previewTheme("material3", "light")) {
                    FlowRow(
                        modifier = Modifier.width(320.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf("手机验证码", "官网登录", "扫码登录").forEach { label ->
                            HongguoChoiceChip(label = label, selected = label == "扫码登录",
                                onClick = {},
                                modifier = Modifier.testTag("large_login_$label"))
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("large_login_手机验证码").assertExists()
        composeRule.onNodeWithTag("large_login_官网登录").assertExists()
        composeRule.onNodeWithTag("large_login_扫码登录").assertExists()
        saveScreenshot("large_font_login", composeRule.onRoot()
            .captureToImage().asAndroidBitmap())
    }

    @Test fun playerSeekTrackSupportsAccessibilitySetProgress() {
        var requestedPosition = -1L
        composeRule.setContent {
            HongguoAppTheme(previewTheme("material3", "dark")) {
                HongguoPlayerSeekTrack(
                    durationMs = 100_000L,
                    playedFraction = .25f,
                    onSeek = { requestedPosition = it },
                    onPreview = {},
                    modifier = Modifier.testTag("accessible_seek"),
                )
            }
        }
        composeRule.onNodeWithTag("accessible_seek")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.5f) }
        composeRule.runOnIdle {
            org.junit.Assert.assertEquals(50_000L, requestedPosition)
        }
        saveScreenshot("seek_accessibility", composeRule.onRoot()
            .captureToImage().asAndroidBitmap())
    }

    @Test fun sharedFeedbackPanelShowsRetryAndSupportsTap() {
        var retryCount = 0
        composeRule.setContent {
            HongguoAppTheme(previewTheme("material3", "light")) {
                Column(Modifier.fillMaxSize()) {
                    HongguoFeedbackPanel(
                        title = "暂时无法加载",
                        message = "临时网络连接失败",
                        kind = HongguoFeedbackKind.ERROR,
                        onRetry = { retryCount++ },
                        tag = "feedback_error_smoke",
                    )
                }
            }
        }
        composeRule.onNodeWithText("暂时无法加载").assertExists()
        composeRule.onNodeWithTag("feedback_error_smoke_retry").performClick()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(1, retryCount) }
        saveScreenshot("feedback_error_material3", composeRule.onRoot()
            .captureToImage().asAndroidBitmap())
    }

    @Test fun sharedPlaybackRetryControlsKeepManualAndStopActions() {
        var retryCount = 0
        var stopCount = 0
        composeRule.setContent {
            HongguoAppTheme(previewTheme("material3", "dark")) {
                Column(Modifier.fillMaxSize().background(Color.Black)) {
                    HongguoVideoRetryActions(
                        onRetry = { retryCount++ },
                        retryLabel = "立即重试",
                        recoveryStatus = "30 秒后再次自动重试",
                        onStopRetry = { stopCount++ },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("video_retry_countdown").assertExists()
        composeRule.onNodeWithTag("video_retry_manual").performClick()
        composeRule.onNodeWithTag("video_retry_stop").performClick()
        composeRule.runOnIdle {
            org.junit.Assert.assertEquals(1, retryCount)
            org.junit.Assert.assertEquals(1, stopCount)
        }
        saveScreenshot("video_retry_controls", composeRule.onRoot()
            .captureToImage().asAndroidBitmap())
    }

    @Test fun posterPlaceholderRendersInMaterialDarkTheme() {
        composeRule.setContent {
            HongguoAppTheme(previewTheme("material3", "dark")) {
                Box(androidx.compose.ui.Modifier.size(120.dp, 180.dp)) {
                    HongguoPosterArtwork(
                        cover = null, title = "占位测试",
                        modifier = androidx.compose.ui.Modifier.size(120.dp, 180.dp),
                        fallback = { Text("暂无封面") },
                    )
                }
            }
        }
        composeRule.onNodeWithText("暂无封面").assertExists()
        val shot = composeRule.onRoot().captureToImage()
        org.junit.Assert.assertTrue(shot.width > 0)
        org.junit.Assert.assertTrue(shot.height > 0)
        saveScreenshot("poster_material3_dark", shot.asAndroidBitmap())
    }

    private fun render(style: String, mode: String) {
        composeRule.setContent {
            var actions by remember { mutableIntStateOf(0) }
            var tab by remember { mutableIntStateOf(0) }
            var episode by remember { mutableIntStateOf(0) }
            var enabled by remember { mutableStateOf(false) }
            HongguoAppTheme(previewTheme(style, mode)) {
                Column(Modifier.fillMaxSize().background(HongguoColors.background)) {
                    HongguoCard {
                        HongguoAction(label = "测试操作", onClick = { actions++ })
                    }
                    Text("点击次数: $actions")
                    HongguoTabs(
                        labels = listOf("观看历史", "我的收藏"),
                        selectedIndex = tab, onSelect = { tab = it },
                        tagPrefix = "smoke_tab",
                    )
                    Text("当前标签: $tab")
                    HongguoEpisodeCell(
                        label = "第1集", selected = true,
                        onClick = { episode++ },
                    )
                    Text("选集点击: $episode")
                    HongguoToggleRow(
                        title = "测试开关", checked = enabled,
                        onCheckedChange = { enabled = it }, tag = "smoke_switch",
                    )
                    Text("开关: $enabled")
                }
            }
        }
        composeRule.onNodeWithText("测试操作").performClick()
        composeRule.onNodeWithText("点击次数: 1").assertExists()
        composeRule.onNodeWithTag("smoke_tab_1").performClick()
        composeRule.onNodeWithText("当前标签: 1").assertExists()
        composeRule.onNodeWithText("第1集").performClick()
        composeRule.onNodeWithText("选集点击: 1").assertExists()
        composeRule.onNodeWithTag("smoke_switch").performClick()
        composeRule.onNodeWithText("开关: true").assertExists()
        val image = composeRule.onRoot().captureToImage()
        org.junit.Assert.assertTrue(image.width > 0)
        org.junit.Assert.assertTrue(image.height > 0)
        saveScreenshot("theme_${style}_${mode}", image.asAndroidBitmap())
    }

    private fun saveScreenshot(name: String, bitmap: Bitmap) {
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android Gradle Test can uninstall the app after instrumentation.
            // App-specific externalFilesDir is deleted on uninstall, so persist
            // CI snapshots through MediaStore into the emulator's public gallery.
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "${name}.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/YHongguo-visual-regression")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = requireNotNull(
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ) { "Cannot create screenshot in Android MediaStore" }
            try {
                val output = requireNotNull(resolver.openOutputStream(uri)) {
                    "Cannot open screenshot MediaStore output"
                }
                output.use {
                    require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                        "Screenshot PNG encoding failed"
                    }
                }
            } finally {
                val published = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, published, null, null)
            }
        } else {
            val directory = File(context.filesDir, "visual-regression")
            require(directory.exists() || directory.mkdirs()) {
                "Cannot create visual regression directory"
            }
            File(directory, "${name}.png").outputStream().use {
                require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }

    private fun previewTheme(style: String, mode: String): HongguoResolvedTheme =
        resolveHongguoTheme(
            context = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().targetContext,
            uiStyle = style, colorSource = "brand", style = "warm",
            mode = mode, systemDark = false,
        )
}
