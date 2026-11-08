package com.supun.sweeper.files

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.cancellation.CancellationException

/** Decimal megabyte, matching how [com.supun.sweeper.util.formatBytes] shows sizes. */
const val MB = 1_000_000L
const val DAY_MS = 24L * 60 * 60 * 1000

/** Every file at least this big is kept by the scanner, so the "large" chips can only go up from here. */
const val COLLECT_MIN_BYTES = 10 * MB

/** A regular file found by [FileScanner]. [path] is a canonical path below the scanned root. */
data class FoundFile(
    val path: String,
    val size: Long,
    val lastModified: Long,
    val inDownload: Boolean,
    val isApk: Boolean,
) {
    val name: String get() = File(path).name
}

/**
 * What one walk collected: files >= [COLLECT_MIN_BYTES], every file under Download/ and every *.apk.
 * The three lists are filters over this set, so changing a threshold never needs a new walk.
 */
class ScanCandidates(val files: List<FoundFile>, val filesSeen: Int, val scannedAt: Long) {
    /** Files of at least [minBytes] (must be >= [COLLECT_MIN_BYTES]), biggest first. */
    fun large(minBytes: Long): List<FoundFile> =
        files.filter { it.size >= minBytes }
            .sortedWith(compareByDescending<FoundFile> { it.size }.thenBy { it.path })

    /** Files under Download/ not modified for at least [minAgeDays] days, oldest first. */
    fun oldDownloads(minAgeDays: Int, now: Long = scannedAt): List<FoundFile> =
        files.filter { it.inDownload && now - it.lastModified >= minAgeDays * DAY_MS }
            .sortedWith(compareBy<FoundFile> { it.lastModified }.thenBy { it.path })

    fun apks(): List<FoundFile> =
        files.filter { it.isApk }
            .sortedWith(compareByDescending<FoundFile> { it.size }.thenBy { it.path })

    fun without(paths: Set<String>): ScanCandidates =
        ScanCandidates(files.filter { it.path !in paths }, filesSeen, scannedAt)
}

/**
 * Walks [root] once and collects candidate files. Never follows symlinks, and skips
 * Android/data, Android/obb, the trash folder and every directory whose name starts with ".".
 */
class FileScanner(root: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val rootPath: Path = root.canonicalFile.toPath()

    /**
     * [isCancelled] is polled for every entry; when it returns true the walk stops and a
     * [CancellationException] is thrown. [onProgress] gets the number of files seen so far.
     */
    fun scan(isCancelled: () -> Boolean = { false }, onProgress: (Int) -> Unit = {}): ScanCandidates {
        val found = ArrayList<FoundFile>()
        var seen = 0
        var cancelled = false

        val visitor = object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (isCancelled()) {
                    cancelled = true
                    return FileVisitResult.TERMINATE
                }
                if (dir == rootPath) return FileVisitResult.CONTINUE
                return if (isSkippedDirectory(rootPath, dir)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (isCancelled()) {
                    cancelled = true
                    return FileVisitResult.TERMINATE
                }
                // Symlinks report isSymbolicLink (not isRegularFile) because links are never followed.
                if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                seen++
                if (seen % 100 == 0) onProgress(seen)
                val rel = rootPath.relativize(file)
                val inDownload = rel.nameCount >= 2 && rel.getName(0).toString() == "Download"
                val isApk = file.fileName.toString().endsWith(".apk", ignoreCase = true)
                val size = attrs.size()
                if (size >= COLLECT_MIN_BYTES || inDownload || isApk) {
                    found.add(FoundFile(file.toString(), size, attrs.lastModifiedTime().toMillis(), inDownload, isApk))
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult = FileVisitResult.CONTINUE
        }
        Files.walkFileTree(rootPath, emptySet<FileVisitOption>(), Int.MAX_VALUE, visitor)
        if (cancelled) throw CancellationException("Scan cancelled")
        onProgress(seen)
        return ScanCandidates(found, seen, clock())
    }
}

/**
 * True if a walk of [rootPath] must not enter [dir] (a directory below it): hidden directories (names starting
 * with "." which includes the trash folder), Android/data and Android/obb. Shared by every walker, so the
 * scanners always agree on what is off limits.
 */
internal fun isSkippedDirectory(rootPath: Path, dir: Path): Boolean {
    val rel = rootPath.relativize(dir)
    if (rel.nameCount == 0) return false
    if (rel.getName(rel.nameCount - 1).toString().startsWith(".")) return true // includes .SweeperTrash
    return rel.nameCount == 2 &&
        rel.getName(0).toString() == "Android" &&
        rel.getName(1).toString().let { it == "data" || it == "obb" }
}
