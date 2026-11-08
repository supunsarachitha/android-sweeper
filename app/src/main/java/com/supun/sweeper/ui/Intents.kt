package com.supun.sweeper.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.Settings

/** Starts [intent]; returns false instead of crashing if no app can handle it. */
fun startSafely(context: Context, intent: Intent): Boolean =
    try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

/** Android's storage manager, or the plain internal storage settings if it is not available. */
fun openStorageSettings(context: Context) {
    if (!startSafely(context, Intent(StorageManager.ACTION_MANAGE_STORAGE))) {
        startSafely(context, Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
    }
}

/** The system "App info" page, where the user can clear an app's cache or storage. */
fun openAppDetails(context: Context, packageName: String) {
    startSafely(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
    )
}
