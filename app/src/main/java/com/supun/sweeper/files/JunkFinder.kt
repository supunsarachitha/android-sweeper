package com.supun.sweeper.files

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.cancellation.CancellationException

/** Top-level folders every Android phone has (lower case). They are never reported as empty, even when they are. */
internal val STANDARD_TOP_LEVEL_FOLDERS: Set<String> = setOf(
    "alarms", "android", "audiobooks", "dcim", "documents", "download", "movies", "music",
    "notifications", "pictures", "podcasts", "recordings", "ringtones",
)

private val TEMP_SUFFIXES = listOf(".tmp", ".temp", ".part", ".crdownload")
private val TEMP_EXACT_NAMES = setOf("Thumbs.db", ".DS_Store")

/** True for names of temporary files: *.tmp, *.temp, *.part, *.crdownload (any case), ~$*, Thumbs.db, .DS_Store. */
fun isTempFileName(name: String): Boolean {
    if (name in TEMP_EXACT_NAMES) return true
    if (name.startsWith("~$")) return true
    val lower = name.lowercase()
    return TEMP_SUFFIXES.any { lower.endsWith(it) }
}

enum class JunkKind { THUMBNAIL, TEMP }

/** True for the two top-level folders that [JunkFinder] must not enter: Android/ and the trash. */
private fun isOffLimitsTopLevel(name: String): Boolean =
    name.equals("Android", ignoreCase = true) || name == TRASH_DIR_NAME

/**
 * What kind of junk the file at [rel] (a path relative to the storage root) is, or null if it is not junk.
 * Nothing inside Android/ or the trash folder is ever junk.
 */
internal fun junkKind(rel: Path): JunkKind? {
    val count = rel.nameCount
    if (count == 0) return null
    val first = rel.getName(0).toString()
    if (isOffLimitsTopLevel(first)) return null
    if (count >= 3 && (first == "DCIM" || first == "Pictures") && rel.getName(1).toString() == ".thumbnails") {
        return JunkKind.THUMBNAIL
    }
    return if (isTempFileName(rel.fileName.toString())) JunkKind.TEMP else null
}

/**
 * True if [file] is, right now, a regular file (not a symlink, no symlinked parent) inside [root] that the junk
 * rules call junk. Checked again right before anything is moved to the trash.
 */
fun isJunkFile(root: File, file: File): Boolean {
    return try {
        val rootPath = root.canonicalFile.toPath()
        val path = file.toPath()
        if (Files.isSymbolicLink(path)) return false
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false
        val canonical = file.canonicalFile.toPath()
        if (canonical == rootPath || !canonical.startsWith(rootPath)) return false
        junkKind(rootPath.relativize(canonical)) != null
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    }
}

/**
 * True if [dir] must never be removed as an empty folder: the root itself, anything outside it, Android/ and
 * everything in it, the trash folder, and the standard top-level folders (any letter case).
 */
internal fun isProtectedFolder(rootPath: Path, dir: Path): Boolean {
    if (dir == rootPath || !dir.startsWith(rootPath)) return true
    val rel = rootPath.relativize(dir)
    val first = rel.getName(0).toString()
    if (isOffLimitsTopLevel(first)) return true
    return rel.nameCount == 1 && first.lowercase() in STANDARD_TOP_LEVEL_FOLDERS
}

/**
 * [emptyFolders]: topmost empty folders (canonical paths, sorted). [thumbnails] and [tempFiles] go to the trash.
 */
class JunkResult(
    val emptyFolders: List<String>,
    val thumbnails: List<FoundFile>,
    val tempFiles: List<FoundFile>,
    val filesSeen: Int,
    val scannedAt: Long,
) {
    /** Bytes the trash-bound categories hold (empty folders hold nothing). */
    val junkBytes: Long get() = thumbnails.sumOf { it.size } + tempFiles.sumOf { it.size }
}

/**
 * Walks [root] once (never following symlinks) and finds empty folders, thumbnail caches and temp files.
 * Unlike the other scanners it walks into hidden folders, but never into the trash folder or Android/.
 */
class JunkFinder(root: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val rootPath: Path = root.canonicalFile.toPath()

    /** Per directory being walked: whether anything but empty folders was seen inside, and its empty children. */
    private class Frame {
        var hasContent = false
        val emptyChildren = ArrayList<String>()
    }

    fun find(isCancelled: () -> Boolean = { false }, onProgress: (Int) -> Unit = {}): JunkResult {
        val emptyFolders = ArrayList<String>()
        val thumbnails = ArrayList<FoundFile>()
        val tempFiles = ArrayList<FoundFile>()
        val stack = ArrayList<Frame>()
        var seen = 0
        var cancelled = false

        val visitor = object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (isCancelled()) {
                    cancelled = true
                    return FileVisitResult.TERMINATE
                }
                if (dir != rootPath && isOffLimitsTopLevel(rootPath.relativize(dir).getName(0).toString())) {
                    // Not walked, so never reported; for its parent (the root) it counts as content.
                    stack.last().hasContent = true
                    return FileVisitResult.SKIP_SUBTREE
                }
                stack.add(Frame())
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (isCancelled()) {
                    cancelled = true
                    return FileVisitResult.TERMINATE
                }
                // Anything that is not a directory (hidden file, symlink, odd node) makes its folder non-empty.
                stack.last().hasContent = true
                if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                seen++
                if (seen % 100 == 0) onProgress(seen)
                val rel = rootPath.relativize(file)
                val kind = junkKind(rel) ?: return FileVisitResult.CONTINUE
                val inDownload = rel.nameCount >= 2 && rel.getName(0).toString() == "Download"
                val isApk = file.fileName.toString().endsWith(".apk", ignoreCase = true)
                val found = FoundFile(file.toString(), attrs.size(), attrs.lastModifiedTime().toMillis(), inDownload, isApk)
                if (kind == JunkKind.THUMBNAIL) thumbnails.add(found) else tempFiles.add(found)
                return FileVisitResult.CONTINUE
            }

            // Unreadable things are treated as content, so they can never make a folder look empty.
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                if (stack.isNotEmpty()) stack.last().hasContent = true
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                val frame = stack.removeAt(stack.size - 1)
                if (exc != null) frame.hasContent = true
                val parent = stack.lastOrNull()
                val empty = !frame.hasContent
                if (empty && !isProtectedFolder(rootPath, dir)) {
                    // Part of an empty chain: only the topmost folder of the chain is reported.
                    parent?.emptyChildren?.add(dir.toString())
                } else {
                    // Has content, or is protected: its empty children are the tops of their chains.
                    emptyFolders.addAll(frame.emptyChildren)
                    if (!empty) parent?.hasContent = true
                }
                return FileVisitResult.CONTINUE
            }
        }
        Files.walkFileTree(rootPath, emptySet<FileVisitOption>(), Int.MAX_VALUE, visitor)
        if (cancelled) throw CancellationException("Scan cancelled")
        onProgress(seen)
        emptyFolders.sort()
        val bySizeThenPath = compareByDescending<FoundFile> { it.size }.thenBy { it.path }
        return JunkResult(
            emptyFolders = emptyFolders,
            thumbnails = thumbnails.sortedWith(bySizeThenPath),
            tempFiles = tempFiles.sortedWith(bySizeThenPath),
            filesSeen = seen,
            scannedAt = clock(),
        )
    }
}

/** [removed]: reported folders that are gone now. [skipped]: folders that failed a check or were not empty any more. */
data class EmptyFolderResult(val removed: Int, val skipped: Int)

/**
 * Removes the empty folders [JunkFinder] reported. Nothing is trusted from the scan: right before deleting a
 * folder it is checked again (inside the root, not a symlink, not protected, subtree still holds no files at
 * all), and `Files.delete` itself refuses a folder that is not empty.
 */
class EmptyFolderRemover(root: File) {
    private val rootPath: Path = root.canonicalFile.toPath()

    fun remove(folders: List<String>): EmptyFolderResult {
        var removed = 0
        var skipped = 0
        // Deepest first, so a folder listed inside another one is handled before its parent.
        val ordered = folders.distinct().sortedByDescending { File(it).toPath().nameCount }
        for (folder in ordered) {
            if (removeOne(folder)) removed++ else skipped++
        }
        return EmptyFolderResult(removed, skipped)
    }

    private fun removeOne(folder: String): Boolean {
        return try {
            val file = File(folder)
            val path = file.toPath()
            if (!path.isAbsolute) return false
            if (Files.isSymbolicLink(path)) return false
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return false
            val canonical = file.canonicalFile.toPath()
            // A different canonical path means a symlink somewhere above the folder.
            if (canonical != path.normalize()) return false
            if (isProtectedFolder(rootPath, canonical)) return false

            // Walk again: the first file of any kind (hidden, symlink, unreadable entry) stops everything.
            val dirs = ArrayList<Path>()
            var holdsFiles = false
            val visitor = object : SimpleFileVisitor<Path>() {
                override fun visitFile(entry: Path, attrs: BasicFileAttributes): FileVisitResult {
                    holdsFiles = true
                    return FileVisitResult.TERMINATE
                }

                override fun visitFileFailed(entry: Path, exc: IOException): FileVisitResult {
                    holdsFiles = true
                    return FileVisitResult.TERMINATE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) {
                        holdsFiles = true
                        return FileVisitResult.TERMINATE
                    }
                    dirs.add(dir) // children are added before their parent
                    return FileVisitResult.CONTINUE
                }
            }
            Files.walkFileTree(canonical, emptySet<FileVisitOption>(), Int.MAX_VALUE, visitor)
            if (holdsFiles) return false
            for (dir in dirs) Files.delete(dir) // deepest first; throws if a dir is not empty
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
