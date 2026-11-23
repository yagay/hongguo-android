package com.yagay.hongguo

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HongguoThemeSmokeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun classicThemeRendersSharedControls() = render("classic")

    @Test fun material3ThemeRendersSharedControls() = render("material3")

    private fun render(style: String) {
        composeRule.setContent {
            HongguoAppTheme(previewTheme(style)) {
                HongguoCard {
                    HongguoAction(label = "测试操作", onClick = {})
                    Text("主题页面")
                }
            }
        }
        composeRule.onNodeWithText("主题页面").assertExists()
        composeRule.onNodeWithText("测试操作").assertExists()
    }

    private fun previewTheme(style: String): HongguoResolvedTheme {
        // Android instrumentation supplies a real Context for the same
        // resolved theme used by production, never a duplicated fake palette.
        return resolveHongguoTheme(
            context = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().targetContext,
            uiStyle = style, colorSource = "brand", style = "warm",
            mode = "light", systemDark = false,
        )
    }
}
