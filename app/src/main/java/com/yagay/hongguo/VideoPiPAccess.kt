package com.yagay.hongguo

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

/**
 * The in-app PiP preference is not an Android permission. Device support
 * and the per-app picture-in-picture AppOp are checked separately.
 */
internal object VideoPiPAccess {
    enum class Status { ALLOWED, BLOCKED, UNSUPPORTED, UNKNOWN }

    /** This decision stays testable without an Android device. */
    fun evaluate(hasDeviceFeature: Boolean, appOpMode: Int?): Status = when {
        !hasDeviceFeature -> Status.UNSUPPORTED
        appOpMode == AppOpsManager.MODE_IGNORED ||
            appOpMode == AppOpsManager.MODE_ERRORED -> Status.BLOCKED
        appOpMode == AppOpsManager.MODE_ALLOWED ||
            appOpMode == AppOpsManager.MODE_DEFAULT -> Status.ALLOWED
        else -> Status.UNKNOWN
    }

    fun status(context: Context): Status {
        val feature = context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_PICTURE_IN_PICTURE
        )
        if (!feature) return Status.UNSUPPORTED
        val mode = runCatching {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps?.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                    Process.myUid(), context.packageName,
                )
            } else {
                appOps?.checkOpNoThrow(
                    AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                    Process.myUid(), context.packageName,
                )
            }
        }.getOrNull()
        return evaluate(hasDeviceFeature = true, appOpMode = mode)
    }
}
