package com.supun.sweeper.storage

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/** "Usage access" special permission: needed to read how much space other apps use. */
object UsageAccess {
    fun isGranted(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Opens this app's entry in Usage access settings, or the general list if that is not possible. */
    fun settingsIntent(context: Context): Intent {
        val direct = Intent(
            Settings.ACTION_USAGE_ACCESS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
        return if (direct.resolveActivity(context.packageManager) != null) {
            direct
        } else {
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        }
    }
}
