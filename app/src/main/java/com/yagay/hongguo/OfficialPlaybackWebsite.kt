package com.yagay.hongguo

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp

/** Only a public series page; the browser handles the official access flow. */
internal object OfficialPlaybackWebsite {
    private val idPattern = Regex("[0-9]{1,30}")

    fun seriesUrl(seriesId: String): String? =
        seriesId.takeIf(idPattern::matches)?.let { "https://hongguoduanju.com/player/$it" }
}

@Composable
internal fun OfficialPlaybackWebsiteButton(onOpen: () -> Unit) {
    TextButton(onClick = onOpen, modifier = Modifier.testTag("official_website_playback")) {
        Text("在红果官网观看", color = Color.White, fontSize = 13.sp)
    }
}
