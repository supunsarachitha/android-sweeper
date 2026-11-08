package com.supun.sweeper.files

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * The files the user has ticked in the duplicates screen. Immutable. The one rule: every group keeps at least one
 * unselected file. [toggle] refuses to break it, [isValid] checks it, [filesToTrash] enforces it.
 */
class DuplicateSelection(val paths: Set<String> = emptySet()) {
    operator fun contains(path: String): Boolean = path in paths

    val size: Int get() = paths.size

    /** True if [path] is the only unselected file of [group], so its checkbox must stay off. */
    fun isLastKept(group: DuplicateGroup, path: String): Boolean {
        if (path in paths) return false
        return group.files.count { it.path !in paths } <= 1
    }

    /** Flips [path]; selecting the last unselected file of [group] (or a path not in the group) is refused. */
    fun toggle(group: DuplicateGroup, path: String): DuplicateSelection {
        if (group.files.none { it.path == path }) return this
        if (path in paths) return DuplicateSelection(paths - path)
        if (isLastKept(group, path)) return this
        return DuplicateSelection(paths + path)
    }

    /** Selects every copy of [group] except the oldest, replacing any earlier choice for this group. */
    fun keepOldest(group: DuplicateGroup): DuplicateSelection {
        val keep = group.files.minWithOrNull(oldestFirst) ?: return this
        val groupPaths = group.files.map { it.path }.toSet()
        return DuplicateSelection(paths - groupPaths + (groupPaths - keep.path))
    }

    /** [keepOldest] for every group. */
    fun selectAllKeepOne(groups: List<DuplicateGroup>): DuplicateSelection =
        groups.fold(this) { acc, group -> acc.keepOldest(group) }

    fun clear(): DuplicateSelection = DuplicateSelection()

    /** Every group keeps at least one unselected file. */
    fun isValid(groups: List<DuplicateGroup>): Boolean =
        groups.all { group -> group.files.any { it.path !in paths } }

    /** Drops paths that are not in [groups] any more. */
    fun retainIn(groups: List<DuplicateGroup>): DuplicateSelection {
        val all = groups.flatMap { g -> g.files.map { it.path } }.toSet()
        return DuplicateSelection(paths.intersect(all))
    }

    /** Total size of the selected files. */
    fun bytes(groups: List<DuplicateGroup>): Long =
        groups.sumOf { g -> g.files.filter { it.path in paths }.sumOf { it.size } }
}

/**
 * The final guard before anything is trashed: the selected files of [groups], or an [IllegalStateException]
 * if any group would lose all its copies.
 */
fun filesToTrash(groups: List<DuplicateGroup>, selection: DuplicateSelection): List<FoundFile> {
    check(selection.isValid(groups)) { "Selection would remove every copy of a file" }
    return groups.flatMap { g -> g.files.filter { it.path in selection.paths } }
}

/** What may be trashed right now ([toTrash]) and what was held back ([skipped]). */
class DuplicateTrashPlan(val toTrash: List<FoundFile>, val skipped: List<FoundFile>)

/**
 * [filesToTrash] plus a last look at the disk: a group is only trashed if at least one kept copy still exists
 * as a regular file with the size and modified time it had when found. A selected file that changed since the scan (size or
 * modified time) is held back too, as it may no longer be a duplicate. Everything held back is in [skipped].
 */
fun planDuplicateTrash(groups: List<DuplicateGroup>, selection: DuplicateSelection): DuplicateTrashPlan {
    filesToTrash(groups, selection) // throws on an invalid selection
    val toTrash = ArrayList<FoundFile>()
    val skipped = ArrayList<FoundFile>()
    for (group in groups) {
        val selected = group.files.filter { it.path in selection.paths }
        if (selected.isEmpty()) continue
        // The kept copy must be exactly as scanned (size AND modified time): if it was edited since, the
        // selected copies may be the only remaining version of the old content.
        val keptAlive = group.files.any { it.path !in selection.paths && unchanged(it, checkTime = true) }
        if (!keptAlive) {
            skipped.addAll(selected)
            continue
        }
        for (file in selected) {
            if (unchanged(file, checkTime = true)) toTrash.add(file) else skipped.add(file)
        }
    }
    return DuplicateTrashPlan(toTrash, skipped)
}

private fun unchanged(file: FoundFile, checkTime: Boolean): Boolean {
    return try {
        val p = File(file.path).toPath()
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return false
        if (Files.size(p) != file.size) return false
        !checkTime || Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis() == file.lastModified
    } catch (e: IOException) {
        false
    } catch (e: SecurityException) {
        false
    }
}

/** [groups] without the files at [removed]; groups left with fewer than two files disappear. */
fun withoutFiles(groups: List<DuplicateGroup>, removed: Set<String>): List<DuplicateGroup> =
    groups.mapNotNull { g ->
        val left = g.files.filter { it.path !in removed }
        if (left.size < 2) null else DuplicateGroup(g.hash, g.size, left)
    }.sortedWith(compareByDescending<DuplicateGroup> { it.wasted }.thenBy { it.oldest.path })
