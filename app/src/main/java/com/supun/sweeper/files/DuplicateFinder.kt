package com.supun.sweeper.files

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/** Files smaller than this are never reported as duplicates. */
const val DUPLICATE_MIN_BYTES = 1024L

/** How much of the start of a file stage 2 hashes. */
const val PREFIX_BYTES = 64 * 1024

/** Buffer for the streamed full hash. */
const val HASH_BUFFER_BYTES = 1024 * 1024

/** Progress stages reported by [DuplicateFinder]. */
const val STAGE_SIZES = 0
const val STAGE_PREFIX = 1
const val STAGE_FULL = 2

/**
 * Files with the same size and the same full SHA-256. [files] are sorted oldest first
 * (lastModified, then path), so `files.first()` is the oldest copy.
 */
data class DuplicateGroup(val hash: String, val size: Long, val files: List<FoundFile>) {
    /** Bytes freed by keeping one copy. */
    val wasted: Long get() = size * (files.size - 1)

    val oldest: FoundFile get() = files.first()
}

/** [groups] sorted by wasted space, biggest first. */
class DuplicateResult(val groups: List<DuplicateGroup>, val filesSeen: Int, val scannedAt: Long) {
    val wasted: Long get() = groups.sumOf { it.wasted }
}

internal val oldestFirst: Comparator<FoundFile> = compareBy<FoundFile> { it.lastModified }.thenBy { it.path }

/**
 * Finds identical files under [root] in three stages: same size, same SHA-256 of the first 64 KiB, same full
 * SHA-256. Walks like [FileScanner] (same skips, never follows symlinks). Files that cannot be read are skipped.
 */
class DuplicateFinder(root: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val rootPath: Path = root.canonicalFile.toPath()

    /**
     * [onProgress] receives (stage, done, total). Stage [STAGE_SIZES] reports files seen (total 0 = unknown);
     * the other stages report files hashed out of the files still in the running.
     * [isCancelled] is polled between files and for every buffer read; when true, a [CancellationException] is thrown.
     */
    fun find(
        isCancelled: () -> Boolean = { false },
        onProgress: (stage: Int, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ): DuplicateResult {
        fun checkCancelled() {
            if (isCancelled()) throw CancellationException("Scan cancelled")
        }

        // Stage 1: walk and group by size.
        val bySize = HashMap<Long, MutableList<FoundFile>>()
        var seen = 0
        val visitor = object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                if (dir == rootPath) return FileVisitResult.CONTINUE
                return if (isSkippedDirectory(rootPath, dir)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                if (!attrs.isRegularFile) return FileVisitResult.CONTINUE // symlinks are not regular files here
                seen++
                if (seen % 100 == 0) onProgress(STAGE_SIZES, seen, 0)
                val size = attrs.size()
                if (size < DUPLICATE_MIN_BYTES) return FileVisitResult.CONTINUE
                val rel = rootPath.relativize(file)
                val inDownload = rel.nameCount >= 2 && rel.getName(0).toString() == "Download"
                val isApk = file.fileName.toString().endsWith(".apk", ignoreCase = true)
                bySize.getOrPut(size) { ArrayList() }
                    .add(FoundFile(file.toString(), size, attrs.lastModifiedTime().toMillis(), inDownload, isApk))
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        }
        Files.walkFileTree(rootPath, emptySet<FileVisitOption>(), Int.MAX_VALUE, visitor)
        onProgress(STAGE_SIZES, seen, 0)
        val sameSize = bySize.values.filter { it.size > 1 }

        // Stage 2: first 64 KiB.
        val prefixTotal = sameSize.sumOf { it.size }
        var done = 0
        onProgress(STAGE_PREFIX, 0, prefixTotal)
        val byPrefix = ArrayList<List<FoundFile>>()
        for (sizeGroup in sameSize) {
            val buckets = HashMap<String, MutableList<FoundFile>>()
            for (file in sizeGroup) {
                checkCancelled()
                val hash = hashFile(file.path, limit = PREFIX_BYTES.toLong(), expectedSize = null, isCancelled = isCancelled)
                done++
                onProgress(STAGE_PREFIX, done, prefixTotal)
                if (hash != null) buckets.getOrPut(hash) { ArrayList() }.add(file)
            }
            buckets.values.filterTo(byPrefix) { it.size > 1 }
        }

        // Stage 3: full content. Files no longer the size we saw (changed meanwhile) are skipped.
        val fullTotal = byPrefix.sumOf { it.size }
        done = 0
        onProgress(STAGE_FULL, 0, fullTotal)
        val groups = ArrayList<DuplicateGroup>()
        for (candidates in byPrefix) {
            val buckets = HashMap<String, MutableList<FoundFile>>()
            for (file in candidates) {
                checkCancelled()
                val hash = hashFile(file.path, limit = Long.MAX_VALUE, expectedSize = file.size, isCancelled = isCancelled)
                done++
                onProgress(STAGE_FULL, done, fullTotal)
                if (hash != null) buckets.getOrPut(hash) { ArrayList() }.add(file)
            }
            for ((hash, files) in buckets) {
                if (files.size > 1) groups.add(DuplicateGroup(hash, files[0].size, files.sortedWith(oldestFirst)))
            }
        }
        groups.sortWith(compareByDescending<DuplicateGroup> { it.wasted }.thenBy { it.oldest.path })
        return DuplicateResult(groups, seen, clock())
    }

    /**
     * Hex SHA-256 of up to [limit] bytes of the file, or null if it cannot be read. With [expectedSize] the whole
     * file must be read and be exactly that long, otherwise it changed and null is returned.
     */
    private fun hashFile(path: String, limit: Long, expectedSize: Long?, isCancelled: () -> Boolean): String? {
        return try {
            val p = File(path).toPath()
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return null
            val digest = MessageDigest.getInstance("SHA-256")
            val bufferSize = if (limit < HASH_BUFFER_BYTES) limit.toInt() else HASH_BUFFER_BYTES
            val buffer = ByteArray(bufferSize)
            var total = 0L
            Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS).use { input: InputStream ->
                while (total < limit) {
                    if (isCancelled()) throw CancellationException("Scan cancelled")
                    val want = minOf(buffer.size.toLong(), limit - total).toInt()
                    val n = input.read(buffer, 0, want)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    total += n
                }
            }
            if (expectedSize != null && total != expectedSize) return null
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }
}
