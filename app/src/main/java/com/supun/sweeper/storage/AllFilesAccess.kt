package com.supun.sweeper.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.supun.sweeper.ui.startSafely

/**
 * "All files access": needed to find files anywhere on shared storage and move them into the trash.
 * Android 11+ uses the special MANAGE_EXTERNAL_STORAGE setting; Android 8-10 use the storage runtime permissions.
 */
object AllFilesAccess {
    /** The runtime permissions to ask for on Android 8-10 (the manifest limits them to API 29). */
    val legacyPermissions = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    val needsSettingsScreen: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            legacyPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }

    /** Android 11+: opens this app's "All files access" page, or the general list if that is not possible. */
    fun openSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val direct = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )
        if (!startSafely(context, direct)) {
            startSafely(context, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}
