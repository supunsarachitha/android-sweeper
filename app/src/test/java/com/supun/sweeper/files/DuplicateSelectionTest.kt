package com.supun.sweeper.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DuplicateSelectionTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File

    @Before fun setUp() {
        root = tmp.newFolder("storage").canonicalFile
    }

    private fun found(path: String, content: String, modified: Long): FoundFile {
        val f = File(root, path)
        f.parentFile!!.mkdirs()
        f.writeText(content)
        f.setLastModified(modified)
        return FoundFile(f.path, f.length(), f.lastModified(), false, false)
    }

    private fun group(vararg files: FoundFile) =
        DuplicateGroup("h", files[0].size, files.sortedWith(oldestFirst))

    private fun trio(prefix: String = "") = group(
        found("${prefix}new.txt", "same", 3000),
        found("${prefix}old.txt", "same", 1000),
        found("${prefix}mid.txt", "same", 2000),
    )

    @Test fun toggleSelectsAndDeselects() {
        val g = trio()
        val a = g.files[1].path
        val s = DuplicateSelection().toggle(g, a)
        assertTrue(a in s)
        assertFalse(a in s.toggle(g, a))
    }

    @Test fun toggleCannotSelectTheLastCopy() {
        val g = trio()
        var s = DuplicateSelection()
        s = s.toggle(g, g.files[0].path).toggle(g, g.files[1].path)
        assertEquals(2, s.size)
        assertTrue(s.isLastKept(g, g.files[2].path))
        val after = s.toggle(g, g.files[2].path)
        assertEquals(2, after.size)
        assertFalse(g.files[2].path in after)
        assertTrue(after.isValid(listOf(g)))
    }

    @Test fun twoCopyGroupAllowsOnlyOneSelection() {
        val g = group(found("a.txt", "same", 1000), found("b.txt", "same", 2000))
        val s = DuplicateSelection().toggle(g, g.files[0].path).toggle(g, g.files[1].path)
        assertEquals(1, s.size)
        assertTrue(s.isValid(listOf(g)))
    }

    @Test fun toggleIgnoresPathsOutsideTheGroup() {
        val g = trio()
        assertEquals(0, DuplicateSelection().toggle(g, "/nowhere").size)
    }

    @Test fun keepOldestSelectsAllButTheOldest() {
        val g = trio()
        val s = DuplicateSelection().keepOldest(g)
        assertEquals(2, s.size)
        assertFalse(File(root, "old.txt").path in s)
        assertTrue(File(root, "new.txt").path in s)
        assertTrue(File(root, "mid.txt").path in s)
    }

    @Test fun keepOldestReplacesAnEarlierChoice() {
        val g = trio()
        val s = DuplicateSelection().toggle(g, File(root, "old.txt").path).keepOldest(g)
        assertEquals(setOf(File(root, "new.txt").path, File(root, "mid.txt").path), s.paths)
    }

    @Test fun selectAllKeepOneKeepsExactlyTheOldestOfEveryGroup() {
        val g1 = trio("one/")
        val g2 = group(found("two/x.txt", "other", 5000), found("two/y.txt", "other", 1000))
        val groups = listOf(g1, g2)
        val s = DuplicateSelection().selectAllKeepOne(groups)
        assertTrue(s.isValid(groups))
        for (g in groups) {
            val kept = g.files.filter { it.path !in s.paths }
            assertEquals(1, kept.size)
            assertEquals(g.files.minWith(oldestFirst).path, kept[0].path)
        }
        assertEquals(3, s.size)
        assertEquals(g1.files.drop(1).sumOf { it.size } + g2.files.drop(1).sumOf { it.size }, s.bytes(groups))
    }

    @Test fun isValidFailsWhenAGroupLosesEveryCopy() {
        val g = trio()
        assertFalse(DuplicateSelection(g.files.map { it.path }.toSet()).isValid(listOf(g)))
        assertTrue(DuplicateSelection().isValid(listOf(g)))
    }

    @Test fun filesToTrashThrowsOnInvalidSelection() {
        val g = trio()
        try {
            filesToTrash(listOf(g), DuplicateSelection(g.files.map { it.path }.toSet()))
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test fun filesToTrashReturnsTheSelectedFiles() {
        val g = trio()
        val s = DuplicateSelection().keepOldest(g)
        assertEquals(setOf("new.txt", "mid.txt"), filesToTrash(listOf(g), s).map { it.name }.toSet())
    }

    @Test fun planTrashesWhenTheKeptCopyIsAlive() {
        val g = trio()
        val plan = planDuplicateTrash(listOf(g), DuplicateSelection().keepOldest(g))
        assertEquals(2, plan.toTrash.size)
        assertEquals(0, plan.skipped.size)
    }

    @Test fun planSkipsAGroupWhoseKeptCopyIsGone() {
        val g1 = trio("one/")
        val g2 = group(found("two/x.txt", "other", 5000), found("two/y.txt", "other", 1000))
        val groups = listOf(g1, g2)
        val s = DuplicateSelection().selectAllKeepOne(groups)
        assertTrue(File(root, "one/old.txt").delete()) // the kept copy of group 1
        val plan = planDuplicateTrash(groups, s)
        assertEquals(setOf("two/x.txt"), plan.toTrash.map { it.path.removePrefix(root.path + "/") }.toSet())
        assertEquals(setOf("new.txt", "mid.txt"), plan.skipped.map { it.name }.toSet())
    }

    @Test fun planSkipsAGroupWhoseKeptCopyChangedSize() {
        val g = trio()
        File(root, "old.txt").writeText("something else entirely")
        val plan = planDuplicateTrash(listOf(g), DuplicateSelection().keepOldest(g))
        assertEquals(0, plan.toTrash.size)
        assertEquals(2, plan.skipped.size)
    }

    @Test fun planSkipsAGroupWhoseKeptCopyWasEditedInPlace() {
        val g = trio()
        File(root, "old.txt").writeText("diff") // same length as "same", different content
        File(root, "old.txt").setLastModified(9999)
        val plan = planDuplicateTrash(listOf(g), DuplicateSelection().keepOldest(g))
        assertEquals(0, plan.toTrash.size)
        assertEquals(setOf("new.txt", "mid.txt"), plan.skipped.map { it.name }.toSet())
    }

    @Test fun planSkipsASelectedFileThatChanged() {
        val g = trio()
        val s = DuplicateSelection().keepOldest(g)
        File(root, "mid.txt").writeText("diff") // same length, new mtime
        File(root, "mid.txt").setLastModified(9999)
        val plan = planDuplicateTrash(listOf(g), s)
        assertEquals(listOf("new.txt"), plan.toTrash.map { it.name })
        assertEquals(listOf("mid.txt"), plan.skipped.map { it.name })
    }

    @Test fun planThrowsOnInvalidSelection() {
        val g = trio()
        try {
            planDuplicateTrash(listOf(g), DuplicateSelection(g.files.map { it.path }.toSet()))
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test fun withoutFilesDropsSmallGroups() {
        val g1 = trio("one/")
        val g2 = group(found("two/x.txt", "other", 5000), found("two/y.txt", "other", 1000))
        val result = withoutFiles(listOf(g1, g2), setOf(g1.files[0].path, g2.files[0].path))
        assertEquals(1, result.size)
        assertEquals(2, result[0].files.size)
    }
}
