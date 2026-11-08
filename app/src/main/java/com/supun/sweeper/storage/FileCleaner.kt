package com.supun.sweeper.storage

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.core.content.pm.PackageInfoCompat
import com.supun.sweeper.files.FileScanner
import com.supun.sweeper.files.FoundFile
import com.supun.sweeper.files.ScanCandidates
import com.supun.sweeper.files.Trash
import com.supun.sweeper.files.TrashItem
import com.supun.sweeper.files.TrashResult
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

data class TrashSummary(val count: Int, val bytes: Long)

/** What the user sees for an APK file: its app name and version, and whether it is already installed. */
data class ApkInfo(val label: String, val versionName: String, val installed: Boolean)

@Suppress("DEPRECATION")
fun storageRoot(): File = Environment.getExternalStorageDirectory()

/** Android side of the file cleaner: runs the pure-Kotlin scanner and trash off the main thread. */
class FileCleaner(context: Context) {
    private val appContext = context.applicationContext
    val root: File = storageRoot()
    private val trash = Trash(root) { System.currentTimeMillis() }

    /** One walk of the storage root. Cancelling the calling coroutine stops the walk. */
    suspend fun scan(onProgress: (Int) -> Unit): ScanCandidates = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        FileScanner(root) { System.currentTimeMillis() }
            .scan(isCancelled = { job?.isActive == false }, onProgress = onProgress)
    }

    suspend fun moveToTrash(files: List<FoundFile>): TrashResult = withContext(Dispatchers.IO) {
        val result = trash.trash(files.map { File(it.path) })
        mediaScan(result.moved.flatMap { listOf(it.originalPath, trash.storedFile(it.id).path) })
        result
    }

    suspend fun trashItems(): List<TrashItem> = withContext(Dispatchers.IO) { trash.list() }

    suspend fun trashSummary(): TrashSummary = withContext(Dispatchers.IO) {
        val items = trash.list()
        TrashSummary(items.size, items.sumOf { it.size })
    }

    /** Restores each id; returns how many worked. */
    suspend fun restore(ids: List<String>): Int = withContext(Dispatchers.IO) {
        var restored = 0
        for (id in ids) {
            val stored = trash.storedFile(id).path
            val target = trash.restore(id)
            if (target != null) {
                restored++
                mediaScan(listOf(stored, target.path))
            }
        }
        restored
    }

    suspend fun deleteNow(id: String): Boolean = withContext(Dispatchers.IO) { trash.deleteNow(id) }

    suspend fun emptyTrash(): Int = withContext(Dispatchers.IO) { trash.emptyAll() }

    suspend fun purgeExpired(): Int = withContext(Dispatchers.IO) { trash.purgeExpired() }

    /** Reads the app name and version from an APK file; null if it cannot be parsed. Call off the main thread. */
    @Suppress("DEPRECATION")
    fun apkInfo(path: String): ApkInfo? {
        val pm = appContext.packageManager
        return try {
            val info = pm.getPackageArchiveInfo(path, 0) ?: return null
            val appInfo = info.applicationInfo ?: return null
            appInfo.sourceDir = path
            appInfo.publicSourceDir = path
            val label = appInfo.loadLabel(pm).toString().ifBlank { info.packageName }
            val installed = try {
                val current = pm.getPackageInfo(info.packageName, 0)
                PackageInfoCompat.getLongVersionCode(current) >= PackageInfoCompat.getLongVersionCode(info)
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
            ApkInfo(label, info.versionName ?: "", installed)
        } catch (e: Exception) {
            null
        }
    }

    private fun mediaScan(paths: List<String>) {
        if (paths.isEmpty()) return
        try {
            MediaScannerConnection.scanFile(appContext, paths.toTypedArray(), null, null)
        } catch (e: Exception) {
            // The gallery catches up on its own later; not worth failing the operation.
        }
    }
}
