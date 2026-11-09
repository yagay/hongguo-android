package com.yagay.hongguo

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/** User-opted periodic checks for saved local series. No cloud account or social requests. */
class TrackingUpdatesWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("web_rewrite_settings", 0)
        if (!prefs.getBoolean("episode_update_notifications", false)) return Result.success()
        val library = LibraryStore(applicationContext)
        val watchLater = runCatching {
            JSONArray(prefs.getString("watch_later_v1", "[]"))
        }.getOrElse { JSONArray() }
        val ids = (library.list(true).map { it.drama.id } +
            (0 until watchLater.length()).mapNotNull {
                watchLater.optJSONObject(it)?.optString("id")
            }).distinct().filter { it.matches(Regex("[0-9]{1,30}")) }.take(60)
        if (ids.isEmpty()) return Result.success()
        val source = NativeHongguoSource()
        var updates = 0
        var works = 0
        var failures = 0
        for (id in ids) {
            if (isStopped) return Result.success()
            try {
                val count = source.detail(id).episodes.size
                if (count <= 0) continue
                val key = "track_episode_" + id
                val known = prefs.getInt(key, -1)
                if (known >= 0 && count > known) {
                    updates += count - known
                    works++
                }
                if (count > known) prefs.edit().putInt(key, count).apply()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failures++ }
        }
        if (works > 0 && notificationsPermitted()) notifyUpdates(works, updates)
        return if (failures == ids.size) Result.retry() else Result.success()
    }

    private fun notificationsPermitted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(applicationContext,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notifyUpdates(works: Int, episodes: Int) {
        val manager = applicationContext.getSystemService(
            Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "hongguo_tracking_updates"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                channelId, "追剧更新", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("收藏的短剧有更新")
            .setContentText("$works 部剧集新增 $episodes 集，打开红果短剧查看")
            .setContentIntent(pending).setAutoCancel(true).build()
        manager.notify(8501, notification)
    }

    companion object {
        private const val NAME = "hongguo_episode_updates"
        fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (!enabled) {
                manager.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<TrackingUpdatesWorker>(
                12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            manager.enqueueUniquePeriodicWork(NAME,
                ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
