package com.supun.sweeper.files

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrashTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private var now = 1_800_000_000_000L
    private lateinit var trash: Trash

    @Before fun setUp() {
        root = tmp.newFolder("storage").canonicalFile
        trash = Trash(root) { now }
    }

    private fun file(path: String, content: String = "hello"): File {
        val f = File(root, path)
        f.parentFile!!.mkdirs()
        f.writeText(content)
        return f
    }

    @Test fun trashThenRestoreRoundTrip() {
        val original = file("Download/report.pdf", "important bytes")
        val result = trash.trash(listOf(original))
        assertEquals(1, result.moved.size)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failed)
        assertFalse(original.exists())
        assertTrue(File(root, "$TRASH_DIR_NAME/.nomedia").exists())
        val item = result.moved.single()
        assertEquals("report.pdf", item.name)
        assertEquals(15L, item.size)
        assertTrue(trash.storedFile(item.id).isFile)

        val restored = trash.restore(item.id)
        assertEquals(original.canonicalFile, restored)
        assertEquals("important bytes", original.readText())
        assertTrue(trash.list().isEmpty())
        assertFalse(trash.storedFile(item.id).exists())
    }

    @Test fun restoreRecreatesMissingParentFolders() {
        val original = file("Download/deep/er/a.txt")
        val item = trash.trash(listOf(original)).moved.single()
        original.parentFile!!.parentFile!!.deleteRecursively()
        assertEquals(original, trash.restore(item.id))
        assertTrue(original.isFile)
    }

    @Test fun restoreConflictUsesRestoredNames() {
        val id1 = trash.trash(listOf(file("Download/a.txt", "one"))).moved.single().id
        val id2 = trash.trash(listOf(file("Download/a.txt", "two"))).moved.single().id
        file("Download/a.txt", "three") // something new took the old name
        assertEquals(File(root, "Download/a (restored).txt"), trash.restore(id1))
        assertEquals(File(root, "Download/a (restored 2).txt"), trash.restore(id2))
        assertEquals("one", File(root, "Download/a (restored).txt").readText())
        assertEquals("two", File(root, "Download/a (restored 2).txt").readText())
        assertEquals("three", File(root, "Download/a.txt").readText())
    }

    @Test fun restoreConflictWithoutExtension() {
        val original = file("Download/README")
        val id = trash.trash(listOf(original)).moved.single().id
        file("Download/README")
        assertEquals(File(root, "Download/README (restored)"), trash.restore(id))
    }

    @Test fun purgeExpiredRemovesOnlyItemsOlderThanSevenDays() {
        val oldItem = trash.trash(listOf(file("a.txt"))).moved.single()
        now += 3 * DAY_MS
        val midItem = trash.trash(listOf(file("b.txt"))).moved.single()
        now += 4 * DAY_MS + 1 // a is 7 days + 1 ms old, b is 4 days + 1 ms old
        assertEquals(1, trash.purgeExpired())
        assertEquals(listOf(midItem.id), trash.list().map { it.id })
        assertFalse(trash.storedFile(oldItem.id).exists())
        assertTrue(trash.storedFile(midItem.id).exists())
        // Exactly 7 days is not "older than 7 days".
        now = midItem.trashedAt + 7 * DAY_MS
        assertEquals(0, trash.purgeExpired())
    }

    @Test fun deleteNowRemovesOneItem() {
        val a = trash.trash(listOf(file("a.txt"))).moved.single()
        val b = trash.trash(listOf(file("b.txt"))).moved.single()
        assertTrue(trash.deleteNow(a.id))
        assertFalse(trash.storedFile(a.id).exists())
        assertEquals(listOf(b.id), trash.list().map { it.id })
        assertFalse(trash.deleteNow(a.id))
    }

    @Test fun emptyAllDeletesEverything() {
        trash.trash(listOf(file("a.txt"), file("b.txt"), file("c/d.txt")))
        assertEquals(3, trash.list().size)
        assertEquals(3, trash.emptyAll())
        assertTrue(trash.list().isEmpty())
        val left = File(root, TRASH_DIR_NAME).list()!!.toSet()
        assertEquals(setOf(".nomedia", "index.tsv"), left)
    }

    @Test fun indexSurvivesReloadFromDisk() {
        val weird = file("Download/we\tird\nname.txt", "x")
        val moved = trash.trash(listOf(file("a.txt", "abc"), weird)).moved
        val fresh = Trash(root) { now }
        val loaded = fresh.list().associateBy { it.id }
        assertEquals(2, loaded.size)
        for (item in moved) assertEquals(item, loaded[item.id])
        assertEquals(weird.canonicalFile, fresh.restore(moved.single { it.name.contains("name") }.id))
        assertEquals("x", weird.readText())
    }

    @Test fun missingStoredFileDropsItsEntry() {
        val a = trash.trash(listOf(file("a.txt"))).moved.single()
        val b = trash.trash(listOf(file("b.txt"))).moved.single()
        assertTrue(trash.storedFile(a.id).delete())
        assertEquals(listOf(b.id), trash.list().map { it.id })
        // And the cleanup was written back to the index.
        val text = File(root, "$TRASH_DIR_NAME/index.tsv").readText()
        assertFalse(text.contains(a.id))
        assertTrue(text.contains(b.id))
    }

    @Test fun unindexedFilesAreDeletedOnlyWhenOlderThanSevenDays() {
        trash.trash(listOf(file("a.txt")))
        val dir = File(root, TRASH_DIR_NAME)
        val fresh = File(dir, "fresh-orphan").also { it.writeText("x") }
        val stale = File(dir, "stale-orphan").also { it.writeText("x") }
        assertTrue(fresh.setLastModified(now - DAY_MS))
        assertTrue(stale.setLastModified(now - 8 * DAY_MS))
        trash.list()
        assertTrue(fresh.exists())
        assertFalse(stale.exists())
        assertTrue(File(dir, ".nomedia").exists())
    }

    @Test fun rejectsPathsOutsideTheRoot() {
        val outside = tmp.newFile("outside.txt")
        val result = trash.trash(listOf(outside))
        assertEquals(0, result.moved.size)
        assertEquals(1, result.skipped)
        assertTrue(outside.exists())
        // A sibling folder whose name merely starts with the root's name.
        val sibling = File(root.parentFile, root.name + "-evil").also { it.mkdirs() }
        val f = File(sibling, "x.txt").also { it.writeText("x") }
        assertEquals(1, trash.trash(listOf(f)).skipped)
        assertTrue(f.exists())
        // ".." tricks resolve to a path outside.
        val tricky = File(root, "../outside.txt")
        assertEquals(1, trash.trash(listOf(tricky)).skipped)
        assertTrue(outside.exists())
    }

    @Test fun rejectsSymlinksAndFilesReachedThroughThem() {
        val outside = tmp.newFile("outside.txt")
        val link = File(root, "link.txt")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertEquals(1, trash.trash(listOf(link)).skipped)
        assertTrue(outside.exists())
        assertTrue(Files.isSymbolicLink(link.toPath()))

        val outDir = tmp.newFolder("outDir")
        File(outDir, "in.txt").writeText("x")
        Files.createSymbolicLink(File(root, "dirlink").toPath(), outDir.toPath())
        assertEquals(1, trash.trash(listOf(File(root, "dirlink/in.txt"))).skipped)
        assertTrue(File(outDir, "in.txt").exists())
    }

    @Test fun rejectsTrashInternalAndAndroidDataPaths() {
        val a = trash.trash(listOf(file("a.txt"))).moved.single()
        assertEquals(1, trash.trash(listOf(trash.storedFile(a.id))).skipped)
        assertTrue(trash.storedFile(a.id).exists())
        assertEquals(1, trash.trash(listOf(File(root, "$TRASH_DIR_NAME/index.tsv"))).skipped)
        assertEquals(1, trash.trash(listOf(file("Android/data/com.x/f.txt"))).skipped)
        assertEquals(1, trash.trash(listOf(file("Android/obb/com.x/f.obb"))).skipped)
        assertEquals(0, trash.trash(listOf(file("Android/media/com.x/ok.txt"))).skipped)
    }

    @Test fun rejectsMissingFilesAndDirectories() {
        val dir = File(root, "folder").also { it.mkdirs() }
        val result = trash.trash(listOf(File(root, "gone.txt"), dir))
        assertEquals(2, result.skipped)
        assertTrue(dir.isDirectory)
    }

    @Test fun trashingTheSameFileTwiceMovesItOnce() {
        val f = file("a.txt")
        val result = trash.trash(listOf(f, f))
        assertEquals(1, result.moved.size)
        assertEquals(1, result.skipped)
    }

    @Test fun contentSurvivesBinary() {
        val f = File(root, "bin.dat").also { it.writeBytes(ByteArray(1000) { i -> i.toByte() }) }
        val id = trash.trash(listOf(f)).moved.single().id
        assertNotNull(trash.restore(id))
        assertArrayEquals(ByteArray(1000) { i -> i.toByte() }, f.readBytes())
    }

    @Test fun restoreOfUnknownIdReturnsNull() {
        assertNull(trash.restore("nope"))
    }

    @Test fun tamperedIndexIsHarmless() {
        trash.trash(listOf(file("a.txt")))
        val index = File(root, "$TRASH_DIR_NAME/index.tsv")
        val outside = tmp.newFile("victim.txt")
        index.appendText("../../etc\t1\t1\t${outside.path}\n")
        index.appendText("not a valid line\n")
        index.appendText("evil-id\t1\t1\t${outside.path}\n") // no stored file: dropped
        assertEquals(1, trash.list().size)
        assertTrue(outside.exists())
    }

    @Test fun daysLeftRoundsUp() {
        val item = TrashItem("x", now, 1, "/a/b.txt")
        assertEquals(7, item.daysLeft(now))
        assertEquals(7, item.daysLeft(now + 1))
        assertEquals(6, item.daysLeft(now + DAY_MS))
        assertEquals(1, item.daysLeft(now + 7 * DAY_MS - 1))
        assertEquals(0, item.daysLeft(now + 7 * DAY_MS))
        assertEquals(0, item.daysLeft(now + 30 * DAY_MS))
    }
}
