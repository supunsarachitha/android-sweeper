package com.supun.sweeper.storage

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.core.content.pm.PackageInfoCompat
import com.supun.sweeper.files.DuplicateFinder
import com.supun.sweeper.files.DuplicateGroup
import com.supun.sweeper.files.DuplicateResult
import com.supun.sweeper.files.DuplicateSelection
import com.supun.sweeper.files.EmptyFolderRemover
import com.supun.sweeper.files.EmptyFolderResult
import com.supun.sweeper.files.FileScanner
import com.supun.sweeper.files.FoundFile
import com.supun.sweeper.files.JunkFinder
import com.supun.sweeper.files.JunkResult
import com.supun.sweeper.files.isJunkFile
import com.supun.sweeper.files.ScanCandidates
import com.supun.sweeper.files.Trash
import com.supun.sweeper.files.TrashItem
import com.supun.sweeper.files.TrashResult
import com.supun.sweeper.files.planDuplicateTrash
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

data class TrashSummary(val count: Int, val bytes: Long)

/** Outcome of trashing duplicates: [trash] is what Trash did; [heldBack] files were not even offered to it. */
data class DuplicateMove(val trash: TrashResult, val heldBack: Int)

/** What the last duplicate scan of this app session found, for the Home card. Lost when the process dies. */
object DuplicateStats {
    @Volatile var wastedBytes: Long? = null
}

/** What the last junk scan of this app session found, for the Home card. Lost when the process dies. */
object JunkStats {
    @Volatile var junkBytes: Long? = null
}

/** One top-level entry (file or folder) of this app's own cache. [size] counts everything below it. */
data class AppCacheEntry(val path: String, val size: Long)

/** Everything one junk scan finds: the files and folders on shared storage, plus this app's own cache. */
class JunkScan(val junk: JunkResult, val appCache: List<AppCacheEntry>) {
    val totalBytes: Long get() = junk.junkBytes + appCache.sumOf { it.size }
}

/** What cleaning did. [trashed] can be undone with [FileCleaner.restore]; the rest is gone for good. */
class JunkCleanResult(val trashed: TrashResult, val folders: EmptyFolderResult, val cacheEntries: Int) {
    val cleaned: Int get() = trashed.moved.size + folders.removed + cacheEntries
}

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

    /** Finds identical files. Cancelling the calling coroutine stops the scan. */
    suspend fun findDuplicates(onProgress: (stage: Int, done: Int, total: Int) -> Unit): DuplicateResult =
        withContext(Dispatchers.IO) {
            val job = coroutineContext[Job]
            DuplicateFinder(root) { System.currentTimeMillis() }
                .find(isCancelled = { job?.isActive == false }, onProgress = onProgress)
        }

    /**
     * Moves the selected duplicates to the trash. Throws [IllegalStateException] (nothing is touched) if the
     * selection would remove every copy of a file; groups whose kept copy has vanished are held back.
     */
    suspend fun trashDuplicates(groups: List<DuplicateGroup>, selection: DuplicateSelection): DuplicateMove =
        withContext(Dispatchers.IO) {
            val plan = planDuplicateTrash(groups, selection)
            val result = trash.trash(plan.toTrash.map { File(it.path) })
            mediaScan(result.moved.flatMap { listOf(it.originalPath, trash.storedFile(it.id).path) })
            DuplicateMove(result, plan.skipped.size)
        }

    /** One walk for empty folders, thumbnail caches and temp files, plus a look at this app's cache. */
    suspend fun scanJunk(onProgress: (Int) -> Unit): JunkScan = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        val junk = JunkFinder(root) { System.currentTimeMillis() }
            .find(isCancelled = { job?.isActive == false }, onProgress = onProgress)
        JunkScan(junk, appCacheEntries())
    }

    /**
     * Cleans the selected junk. Files are re-checked against the junk rules and then go to the trash; empty
     * folders are re-validated and removed directly; the app cache is deleted directly.
     */
    suspend fun cleanJunk(
        files: List<FoundFile>,
        emptyFolders: List<String>,
        cacheEntries: List<AppCacheEntry>,
    ): JunkCleanResult = withContext(Dispatchers.IO) {
        val stillJunk = files.filter { isJunkFile(root, File(it.path)) }
        val trashed = trash.trash(stillJunk.map { File(it.path) })
        mediaScan(trashed.moved.flatMap { listOf(it.originalPath, trash.storedFile(it.id).path) })
        val folders = EmptyFolderRemover(root).remove(emptyFolders)
        val deleted = deleteAppCache(cacheEntries)
        JunkCleanResult(
            TrashResult(trashed.moved, trashed.skipped + (files.size - stillJunk.size), trashed.failed),
            folders,
            deleted,
        )
    }

    private fun cacheDirs(): List<File> =
        listOfNotNull(appContext.cacheDir, appContext.externalCacheDir).mapNotNull {
            try {
                it.canonicalFile
            } catch (e: IOException) {
                null
            }
        }.distinct()

    private fun appCacheEntries(): List<AppCacheEntry> {
        val entries = ArrayList<AppCacheEntry>()
        for (dir in cacheDirs()) {
            val children = dir.listFiles() ?: continue
            for (child in children) entries.add(AppCacheEntry(child.path, treeSize(child.toPath())))
        }
        return entries.sortedWith(compareByDescending<AppCacheEntry> { it.size }.thenBy { it.path })
    }

    /** Total size of regular files below [start], never following symlinks. */
    private fun treeSize(start: Path): Long {
        var total = 0L
        try {
            Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (e: IOException) {
            // Counts what could be read.
        }
        return total
    }

    /** Deletes the given cache entries, but only ones that sit directly inside this app's cache folders. */
    private fun deleteAppCache(entries: List<AppCacheEntry>): Int {
        val dirs = cacheDirs().map { it.path }.toSet()
        var deleted = 0
        for (entry in entries) {
            val file = File(entry.path)
            val parent = file.parentFile?.let { try { it.canonicalPath } catch (e: IOException) { null } }
            if (parent == null || parent !in dirs) continue
            if (deleteTree(file.toPath())) deleted++
        }
        return deleted
    }

    /** Deletes a file or folder tree. Symlinks are removed as links and never followed. */
    private fun deleteTree(start: Path): Boolean {
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return false
        return try {
            Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
            true
        } catch (e: IOException) {
            false
        }
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
