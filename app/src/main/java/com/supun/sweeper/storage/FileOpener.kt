package com.supun.sweeper.storage

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.supun.sweeper.files.OpenCheck
import com.supun.sweeper.files.OpenResult
import com.supun.sweeper.files.OpenRules
import com.supun.sweeper.files.PathGuard
import java.io.File

/**
 * Opens a file in another app (Android's "Open with" chooser; the package installer for APK files).
 * Read-only: the other app gets a content:// link with FLAG_GRANT_READ_URI_PERMISSION and nothing else.
 */
object FileOpener {
    /** Tests set this to capture the final intent instead of starting it. */
    internal var interceptor: ((Intent) -> Unit)? = null

    fun open(context: Context, path: String): OpenResult {
        val check = OpenRules.check(File(path), PathGuard(storageRoot()))
        val file = when (check) {
            is OpenCheck.Allowed -> check.file
            OpenCheck.Missing -> return OpenResult.Missing
            OpenCheck.NotAllowed -> return OpenResult.NotAllowed
        }
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mime = OpenRules.mimeType(file.name) { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // The package installer is one fixed screen; every other type goes through the chooser.
            val launch = if (OpenRules.isApk(file.name)) view else Intent.createChooser(view, "Open with")
            launch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val hook = interceptor
            if (hook != null) {
                hook(launch)
                return OpenResult.Started
            }
            if (context.packageManager.queryIntentActivities(view, 0).isEmpty()) return OpenResult.NoApp
            context.startActivity(launch)
            OpenResult.Started
        } catch (e: ActivityNotFoundException) {
            OpenResult.NoApp
        } catch (e: IllegalArgumentException) {
            OpenResult.NotAllowed // FileProvider has no root for this path
        } catch (e: SecurityException) {
            OpenResult.Failed
        }
    }
}
