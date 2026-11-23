package com.yagay.hongguo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.ui.graphics.Color
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
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
        val directory = context.getExternalFilesDir("visual-regression")
            ?: File(context.filesDir, "visual-regression")
        require(directory.exists() || directory.mkdirs()) {
            "Cannot create visual regression directory"
        }
        File(directory, "${name}.png").outputStream().use { output ->
            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Screenshot PNG encoding failed"
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
