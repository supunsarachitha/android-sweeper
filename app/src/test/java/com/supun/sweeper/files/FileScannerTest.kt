package com.supun.sweeper.files

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileScannerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private var now = 1_800_000_000_000L

    @Before fun setUp() {
        root = tmp.newFolder("storage").canonicalFile
    }

    private fun file(path: String, size: Long = 10, ageDays: Int = 0): File {
        val f = File(root, path)
        f.parentFile!!.mkdirs()
        RandomAccessFile(f, "rw").use { it.setLength(size) } // sparse, cheap even for big sizes
        assertTrue(f.setLastModified(now - ageDays * DAY_MS))
        return f
    }

    private fun scan() = FileScanner(root) { now }.scan()

    private fun names(list: List<FoundFile>) = list.map { it.name }

    @Test fun largeThresholdIsInclusive() {
        file("DCIM/exact.bin", 10 * MB)
        file("DCIM/below.bin", 10 * MB - 1)
        file("DCIM/big.bin", 60 * MB)
        val result = scan()
        assertEquals(listOf("big.bin", "exact.bin"), names(result.large(10 * MB)))
        assertEquals(listOf("big.bin"), names(result.large(50 * MB)))
        assertEquals(listOf("big.bin"), names(result.large(60 * MB)))
        assertEquals(emptyList<String>(), names(result.large(61 * MB)))
    }

    @Test fun largeIsSortedBySizeDescending() {
        file("a.bin", 20 * MB)
        file("b.bin", 40 * MB)
        file("c.bin", 30 * MB)
        assertEquals(listOf("b.bin", "c.bin", "a.bin"), names(scan().large(10 * MB)))
    }

    @Test fun oldDownloadsUseTheClockAndOnlyDownloadFolder() {
        file("Download/old.pdf", ageDays = 120)
        file("Download/sub/older.pdf", ageDays = 400)
        file("Download/exactly90.pdf", ageDays = 90)
        file("Download/new.pdf", ageDays = 89)
        file("Documents/old-but-not-download.pdf", ageDays = 500)
        val result = scan()
        assertEquals(listOf("older.pdf", "old.pdf", "exactly90.pdf"), names(result.oldDownloads(90)))
        assertEquals(listOf("older.pdf"), names(result.oldDownloads(365)))
        assertEquals(4, result.oldDownloads(30).size)
        // Moving the clock forward makes the new file old, without scanning again.
        assertTrue("new.pdf" in names(result.oldDownloads(90, now = now + 10 * DAY_MS)))
    }

    @Test fun apksAreFoundEverywhereIncludingSubfolders() {
        file("Download/a.apk")
        file("Telegram/Docs/deep/b.APK")
        file("c.apk")
        file("notes.txt")
        assertEquals(setOf("a.apk", "b.APK", "c.apk"), names(scan().apks()).toSet())
    }

    @Test fun skipsAndroidDataObbTrashAndDotDirs() {
        file("Android/data/com.x/big.bin", 20 * MB)
        file("Android/obb/com.x/big2.bin", 20 * MB)
        file("$TRASH_DIR_NAME/big3.bin", 20 * MB)
        file(".hidden/big4.bin", 20 * MB)
        file("DCIM/.thumbnails/big5.bin", 20 * MB)
        file("Download/.cache/x.apk")
        file("Android/media/com.x/ok.bin", 20 * MB) // only data and obb are skipped
        file("visible.bin", 20 * MB)
        val result = scan()
        assertEquals(setOf("ok.bin", "visible.bin"), names(result.files).toSet())
    }

    @Test fun symlinkToBigFileOutsideRootIsNotReported() {
        val outside = tmp.newFolder("outside")
        val big = File(outside, "secret.bin")
        RandomAccessFile(big, "rw").use { it.setLength(100 * MB) }
        Files.createSymbolicLink(File(root, "Download").also { it.mkdirs() }.toPath().resolve("link.bin"), big.toPath())
        Files.createSymbolicLink(File(root, "link2.apk").toPath(), big.toPath())
        val result = scan()
        assertEquals(emptyList<String>(), names(result.files))
        assertEquals(0, result.filesSeen)
    }

    @Test fun symlinkedDirectoryIsNotDescended() {
        val outside = tmp.newFolder("outsideDir")
        RandomAccessFile(File(outside, "inside-link.bin"), "rw").use { it.setLength(50 * MB) }
        File(outside, "x.apk").writeText("x")
        Files.createSymbolicLink(File(root, "linked").toPath(), outside.toPath())
        file("real.bin", 20 * MB)
        assertEquals(listOf("real.bin"), names(scan().files))
    }

    @Test fun cancellationStopsTheWalk() {
        repeat(5) { file("d$it/f.bin", 20 * MB) }
        var calls = 0
        try {
            FileScanner(root) { now }.scan(isCancelled = { ++calls > 3 })
            throw AssertionError("expected cancellation")
        } catch (e: java.util.concurrent.CancellationException) {
            assertTrue(calls > 3)
        }
    }

    @Test fun reportsProgressAsFilesSeen() {
        repeat(250) { file("many/f$it.txt") }
        val seen = ArrayList<Int>()
        val result = FileScanner(root) { now }.scan(onProgress = { seen.add(it) })
        assertEquals(250, result.filesSeen)
        assertEquals(250, seen.last())
        assertFalse(seen.isEmpty())
    }

    @Test fun withoutRemovesPaths() {
        val a = file("a.bin", 20 * MB)
        file("b.bin", 20 * MB)
        val result = scan().without(setOf(a.path))
        assertEquals(listOf("b.bin"), names(result.large(10 * MB)))
    }
}
