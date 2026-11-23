package com.yagay.hongguo

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    private fun render(style: String, mode: String) {
        composeRule.setContent {
            var actions by remember { mutableIntStateOf(0) }
            var tab by remember { mutableIntStateOf(0) }
            var episode by remember { mutableIntStateOf(0) }
            var enabled by remember { mutableStateOf(false) }
            HongguoAppTheme(previewTheme(style, mode)) {
                Column {
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
    }

    private fun previewTheme(style: String, mode: String): HongguoResolvedTheme =
        resolveHongguoTheme(
            context = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().targetContext,
            uiStyle = style, colorSource = "brand", style = "warm",
            mode = mode, systemDark = false,
        )
}
