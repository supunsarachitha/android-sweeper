package com.supun.sweeper.files

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

const val TRASH_DIR_NAME = ".SweeperTrash"
const val TRASH_MAX_AGE_MS = 7 * DAY_MS

/** One file waiting in the trash. [originalPath] is where [restore][Trash.restore] puts it back. */
data class TrashItem(val id: String, val trashedAt: Long, val size: Long, val originalPath: String) {
    val name: String get() = File(originalPath).name
    val folder: String get() = File(originalPath).parent ?: ""

    /** Whole days left before the trash deletes it for good (rounded up, never negative). */
    fun daysLeft(now: Long, maxAgeMillis: Long = TRASH_MAX_AGE_MS): Int {
        val left = trashedAt + maxAgeMillis - now
        return if (left <= 0) 0 else ((left + DAY_MS - 1) / DAY_MS).toInt()
    }
}

/** [skipped]: failed the safety checks (or vanished). [failed]: the move itself failed; the file stayed put. */
data class TrashResult(val moved: List<TrashItem>, val skipped: Int, val failed: Int)

/**
 * Decides which files may be touched: regular files (not symlinks) whose canonical path is inside the
 * canonical storage root, and not inside the trash folder or Android/data|obb.
 */
class PathGuard(storageRoot: File) {
    val root: File = storageRoot.canonicalFile
    private val rootPrefix = root.path.trimEnd('/') + "/"
    private val trashPrefix = File(root, TRASH_DIR_NAME).path + "/"
    private val forbiddenPrefixes = listOf(
        trashPrefix,
        File(root, "Android/data").path + "/",
        File(root, "Android/obb").path + "/",
    )

    /** True if [canonical] (a canonical path) may be acted on: inside the root, not in a forbidden folder. */
    fun isAllowedLocation(canonical: String): Boolean =
        canonical.startsWith(rootPrefix) && forbiddenPrefixes.none { canonical.startsWith(it) } &&
            canonical != File(root, "Android/data").path && canonical != File(root, "Android/obb").path

    /** The canonical file if [file] passes every check, else null. */
    fun validate(file: File): File? {
        return try {
            val path: Path = file.toPath()
            if (Files.isSymbolicLink(path)) return null
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
            val canonical = file.canonicalFile
            if (!isAllowedLocation(canonical.path)) return null
            if (!Files.isRegularFile(canonical.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
            canonical
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }
}

/**
 * The in-app trash: `<storageRoot>/.SweeperTrash/`, holding a `.nomedia` file, an `index.tsv` and one
 * file per trashed item named by its id. Nothing is kept in memory between calls: every operation reads
 * the index from disk, so any number of [Trash] objects for the same root agree with each other.
 */
class Trash(storageRoot: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val guard = PathGuard(storageRoot)
    private val dir = File(guard.root, TRASH_DIR_NAME)
    private val indexFile = File(dir, INDEX_NAME)

    /** Where the trashed file with [id] is stored. */
    fun storedFile(id: String): File = File(dir, id)

    /** Items, newest first. Drops index entries whose file is missing and prunes old orphan files. */
    fun list(): List<TrashItem> = synchronized(LOCK) { load().sortedByDescending { it.trashedAt } }

    /**
     * Moves each file into the trash. Files failing the safety checks are skipped; failed moves are left alone.
     *
     * Write-ahead: every planned item is recorded in the index BEFORE anything moves. If the app dies mid-batch,
     * the entries whose move never happened point at missing stored files and are dropped on the next load,
     * while those files are still in their original place. A moved file can therefore never be unrecorded.
     */
    fun trash(files: List<File>): TrashResult = synchronized(LOCK) {
        val items = load()
        var skipped = 0
        val planned = ArrayList<Pair<TrashItem, Path>>()
        val seen = HashSet<String>()
        for (file in files) {
            val canonical = guard.validate(file)
            if (canonical == null || !seen.add(canonical.path)) {
                skipped++
                continue
            }
            val size = try {
                Files.size(canonical.toPath())
            } catch (e: Exception) {
                skipped++
                continue
            }
            planned.add(TrashItem(UUID.randomUUID().toString(), clock(), size, canonical.path) to canonical.toPath())
        }
        if (planned.isEmpty()) return@synchronized TrashResult(emptyList(), skipped, 0)
        try {
            writeIndex(items + planned.map { it.first })
        } catch (e: Exception) {
            return@synchronized TrashResult(emptyList(), skipped, planned.size) // nothing moved
        }
        val moved = ArrayList<TrashItem>()
        var failed = 0
        for ((item, source) in planned) {
            try {
                Files.move(source, storedFile(item.id).toPath()) // no REPLACE_EXISTING
                moved.add(item)
            } catch (e: Exception) {
                failed++
            }
        }
        if (failed > 0) {
            try {
                writeIndex(items + moved)
            } catch (e: Exception) {
                // Entries of files that did not move are dropped on the next load (no stored file).
            }
        }
        TrashResult(moved, skipped, failed)
    }

    /**
     * Puts the item back. If the original path is taken, uses "name (restored).ext", then
     * "name (restored 2).ext" and so on. Returns the restored file, or null if it could not be restored.
     */
    fun restore(id: String): File? = synchronized(LOCK) {
        val items = load()
        val item = items.firstOrNull { it.id == id } ?: return@synchronized null
        val stored = storedFile(id)
        try {
            val original = File(item.originalPath)
            val parent = original.parentFile?.toPath()?.normalize()?.toFile() ?: return@synchronized null
            if (!guard.isAllowedLocation(File(parent, "x").path)) return@synchronized null
            parent.mkdirs()
            val canonicalParent = parent.canonicalFile
            if (!guard.isAllowedLocation(File(canonicalParent, "x").path)) return@synchronized null
            val target = freeName(canonicalParent, original.name)
            Files.move(stored.toPath(), target.toPath()) // no REPLACE_EXISTING
            try {
                writeIndex(items.filter { it.id != id })
            } catch (e: Exception) {
                // The stale entry is dropped on the next load because its stored file is gone.
            }
            target
        } catch (e: Exception) {
            null
        }
    }

    /** Permanently deletes one item. */
    fun deleteNow(id: String): Boolean = synchronized(LOCK) {
        val items = load()
        if (items.none { it.id == id }) return@synchronized false
        try {
            Files.deleteIfExists(storedFile(id).toPath())
            writeIndex(items.filter { it.id != id })
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Permanently deletes everything in the trash. Returns how many items were deleted. */
    fun emptyAll(): Int = synchronized(LOCK) {
        val items = load()
        val remaining = ArrayList<TrashItem>()
        var deleted = 0
        for (item in items) {
            try {
                Files.deleteIfExists(storedFile(item.id).toPath())
                deleted++
            } catch (e: Exception) {
                remaining.add(item)
            }
        }
        if (items.isNotEmpty()) {
            try {
                writeIndex(remaining)
            } catch (e: Exception) {
                // Entries of deleted files are dropped on the next load.
            }
        }
        deleted
    }

    /** Deletes items trashed more than [maxAgeMillis] ago. Returns how many were deleted. */
    fun purgeExpired(maxAgeMillis: Long = TRASH_MAX_AGE_MS): Int = synchronized(LOCK) {
        val items = load()
        val now = clock()
        val keep = ArrayList<TrashItem>()
        var deleted = 0
        for (item in items) {
            if (now - item.trashedAt > maxAgeMillis) {
                try {
                    Files.deleteIfExists(storedFile(item.id).toPath())
                    deleted++
                } catch (e: Exception) {
                    keep.add(item)
                }
            } else {
                keep.add(item)
            }
        }
        if (deleted > 0) {
            try {
                writeIndex(keep)
            } catch (e: Exception) {
                // Entries of deleted files are dropped on the next load.
            }
        }
        deleted
    }

    // --- internals -------------------------------------------------------------------------------

    private fun ensureDir() {
        if (!dir.isDirectory) Files.createDirectories(dir.toPath())
        val nomedia = File(dir, ".nomedia")
        if (!Files.exists(nomedia.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.createFile(nomedia.toPath())
    }

    /**
     * Reads the index and drops entries without a stored file. Files in the trash folder that have no entry
     * (which write-ahead indexing should make impossible) are never deleted: they are adopted as items with a
     * fresh 7 days, restorable to Download/Recovered from trash/.
     */
    private fun load(): MutableList<TrashItem> {
        if (!dir.isDirectory) return ArrayList()
        val parsed = readIndexFile()
        val kept = parsed.filter { Files.isRegularFile(storedFile(it.id).toPath(), LinkOption.NOFOLLOW_LINKS) }
        val ids = kept.map { it.id }.toHashSet()
        val adopted = ArrayList<TrashItem>()
        dir.listFiles()?.forEach { f ->
            if (f.name in RESERVED || f.name in ids) return@forEach
            try {
                if (!Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)) return@forEach
                var id = f.name
                if (!ID_REGEX.matches(id)) {
                    id = UUID.randomUUID().toString()
                    Files.move(f.toPath(), storedFile(id).toPath())
                }
                val recovered = File(guard.root, "Download/$RECOVERED_DIR/${f.name}").path
                adopted.add(TrashItem(id, clock(), Files.size(storedFile(id).toPath()), recovered))
                ids.add(id)
            } catch (e: Exception) {
                // Left as is; retried on the next load.
            }
        }
        val all = kept + adopted
        if (kept.size != parsed.size || adopted.isNotEmpty()) {
            try {
                writeIndex(all)
            } catch (e: Exception) {
                // Retried on the next load.
            }
        }
        return all.toMutableList()
    }

    private fun readIndexFile(): List<TrashItem> {
        if (!indexFile.isFile) return emptyList()
        val lines = try {
            Files.readAllLines(indexFile.toPath(), Charsets.UTF_8)
        } catch (e: IOException) {
            return emptyList()
        }
        val items = ArrayList<TrashItem>()
        for (line in lines) {
            val parts = line.split('\t', limit = 4)
            if (parts.size != 4) continue
            val id = parts[0]
            val at = parts[1].toLongOrNull() ?: continue
            val size = parts[2].toLongOrNull() ?: continue
            if (!ID_REGEX.matches(id)) continue // an id is a file name; never let it hold a path
            val original = unescape(parts[3])
            if (original.isEmpty()) continue
            items.add(TrashItem(id, at, size, original))
        }
        return items
    }

    private fun writeIndex(items: List<TrashItem>) {
        ensureDir()
        val text = items.joinToString(separator = "") { "${it.id}\t${it.trashedAt}\t${it.size}\t${escape(it.originalPath)}\n" }
        val tmp = File(dir, INDEX_TMP_NAME)
        Files.write(tmp.toPath(), text.toByteArray(Charsets.UTF_8))
        try {
            Files.move(tmp.toPath(), indexFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun freeName(parent: File, name: String): File {
        val first = File(parent, name)
        if (!Files.exists(first.toPath(), LinkOption.NOFOLLOW_LINKS)) return first
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val label = if (n == 1) "restored" else "restored $n"
            val candidate = File(parent, "$base ($label)$ext")
            if (!Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) return candidate
            n++
        }
    }

    companion object {
        private val LOCK = Any()
        private const val INDEX_NAME = "index.tsv"
        private const val INDEX_TMP_NAME = "index.tsv.tmp"
        const val RECOVERED_DIR = "Recovered from trash"
        private val RESERVED = setOf(".nomedia", INDEX_NAME, INDEX_TMP_NAME)
        private val ID_REGEX = Regex("[A-Za-z0-9-]{1,64}")

        internal fun escape(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '\t' -> sb.append("\\t")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        internal fun unescape(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> sb.append('\t')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        else -> sb.append(s[i + 1])
                    }
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }
    }
}
