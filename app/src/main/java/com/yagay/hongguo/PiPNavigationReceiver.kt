package com.yagay.hongguo

import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/** Real Android PiP commands, distinct from MediaSession notification buttons. */
internal object PiPControls {
    const val PREVIOUS = "com.yagay.hongguo.pip.PREVIOUS"
    const val TOGGLE_PLAYBACK = "com.yagay.hongguo.pip.TOGGLE_PLAYBACK"
    const val NEXT = "com.yagay.hongguo.pip.NEXT"

    /** Exactly three stable controls: previous, pause/play, next. */
    fun actions(
        context: Context,
        available: BackgroundNavigationPolicy.Availability,
        isPlaying: Boolean,
        hasPlayer: Boolean,
    ): List<RemoteAction> {
        if (!hasPlayer) return emptyList()
        return listOf(
            action(context, PREVIOUS, 71, android.R.drawable.ic_media_previous,
                "上一集／上一部", available.previous),
            action(context, TOGGLE_PLAYBACK, 72,
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
                if (isPlaying) "暂停" else "播放", true),
            action(context, NEXT, 73, android.R.drawable.ic_media_next,
                "下一集／下一部", available.next),
        )
    }

    private fun action(
        context: Context,
        action: String,
        requestCode: Int,
        iconRes: Int,
        title: String,
        enabled: Boolean,
    ): RemoteAction {
        // Explicit component + non-exported receiver. Distinct request codes
        // prevent Android from reusing the previous button's PendingIntent.
        val intent = Intent(context, PiPNavigationReceiver::class.java)
            .setAction(action)
        val pending = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(
            Icon.createWithResource(context, iconRes),
            title, title, pending,
        ).apply { isEnabled = enabled }
    }
}

/** PiP has no clickable Compose UI; controls arrive via PendingIntent. */
class PiPNavigationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = BackgroundPlaybackService.active ?: return
        when (intent.action) {
            PiPControls.PREVIOUS -> service.navigateFromPip(-1)
            PiPControls.NEXT -> service.navigateFromPip(1)
            PiPControls.TOGGLE_PLAYBACK -> service.togglePlaybackFromPip()
        }
    }
}
