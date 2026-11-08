package com.supun.sweeper.files

import java.io.File
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JunkFinderTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private var now = 1_800_000_000_000L

    @Before fun setUp() {
        root = tmp.newFolder("storage").canonicalFile
    }

    private fun dir(path: String): File = File(root, path).also { assertTrue(it.isDirectory || it.mkdirs()) }

    private fun file(path: String, content: String = "hello"): File {
        val f = File(root, path)
        f.parentFile!!.mkdirs()
        f.writeText(content)
        return f
    }

    private fun find() = JunkFinder(root) { now }.find()

    private fun empties(): List<String> = find().emptyFolders.map { it.removePrefix(root.path + "/") }

    private fun temps(): List<String> = find().tempFiles.map { it.path.removePrefix(root.path + "/") }

    // --- empty folders ---------------------------------------------------------------------------

    @Test fun nestedEmptyChainReportsOnlyTheTop() {
        file("Download/keep.txt")
        dir("Download/a/b/c")
        assertEquals(listOf("Download/a"), empties())
    }

    @Test fun siblingChainsAreEachReportedOnce() {
        file("Download/keep.txt")
        dir("Download/a/b")
        dir("Download/x")
        assertEquals(listOf("Download/a", "Download/x"), empties())
    }

    @Test fun folderWithAFileDeepInsideIsNotEmptyButItsEmptyChildIs() {
        file("Download/a/b/file.txt")
        dir("Download/a/empty/deeper")
        assertEquals(listOf("Download/a/empty"), empties())
    }

    @Test fun folderWithOnlyAHiddenFileIsNotEmpty() {
        file("Download/a/.hidden")
        dir("Download/a/sub")
        assertEquals(listOf("Download/a/sub"), empties())
    }

    @Test fun folderWithOnlyASymlinkIsNotEmpty() {
        val target = tmp.newFile("outside.txt")
        dir("Download/linked")
        Files.createSymbolicLink(File(root, "Download/linked/link").toPath(), target.toPath())
        dir("Download/linkedDir")
        val outsideDir = tmp.newFolder("outsideDir")
        Files.createSymbolicLink(File(root, "Download/linkedDir/dirLink").toPath(), outsideDir.toPath())
        assertEquals(emptyList<String>(), empties())
        assertTrue(outsideDir.isDirectory) // never followed or touched
    }

    @Test fun standardTopLevelFoldersAreNeverReportedInAnyCase() {
        for (name in listOf("Alarms", "Android", "Audiobooks", "DCIM", "Documents", "Download", "Movies", "Music",
            "Notifications", "Pictures", "Podcasts", "Recordings", "Ringtones", "dcim", "MUSIC", "download")) {
            dir(name)
        }
        assertEquals(emptyList<String>(), empties())
    }

    @Test fun emptyChildOfAnEmptyStandardFolderIsReported() {
        dir("Pictures/old/2019")
        assertEquals(listOf("Pictures/old"), empties())
    }

    @Test fun nonStandardTopLevelFolderIsReported() {
        dir("Stuff/inner")
        assertEquals(listOf("Stuff"), empties())
    }

    @Test fun standardNameBelowTopLevelIsStillReported() {
        file("Download/keep.txt")
        dir("Download/Music")
        assertEquals(listOf("Download/Music"), empties())
    }

    @Test fun nothingUnderAndroidIsReported() {
        dir("Android/data/com.example/cache")
        dir("Android/obb/com.example")
        dir("Android/media/com.example/empty")
        dir("android/other/empty")
        assertEquals(emptyList<String>(), empties())
    }

    @Test fun rootIsNeverReported() {
        assertEquals(emptyList<String>(), empties())
    }

    @Test fun hiddenEmptyFoldersAreReportedButNotTheTrash() {
        file("Download/keep.txt")
        dir("Download/.hiddenEmpty/inner")
        dir(".SweeperTrash/leftover")
        assertEquals(listOf("Download/.hiddenEmpty"), empties())
    }

    @Test fun hiddenFolderHoldingAFileCountsAsContentForItsParent() {
        file("Stuff/.cache/data.bin")
        assertEquals(emptyList<String>(), empties())
    }

    @Test fun trashFolderCountsAsContentForTheRoot() {
        dir(".SweeperTrash")
        dir("Stuff")
        assertEquals(listOf("Stuff"), empties())
    }

    @Test fun symlinkedFolderIsNotWalkedIntoOrReported() {
        val outsideDir = tmp.newFolder("outsideDir")
        File(outsideDir, "inner").mkdirs()
        file("Download/keep.txt")
        Files.createSymbolicLink(File(root, "Download/link").toPath(), outsideDir.toPath())
        assertEquals(emptyList<String>(), empties())
    }

    // --- thumbnails ------------------------------------------------------------------------------

    @Test fun thumbnailsUnderDcimAndPicturesAreFoundRecursively() {
        file("DCIM/.thumbnails/a.jpg", "12345")
        file("DCIM/.thumbnails/sub/b.jpg")
        file("Pictures/.thumbnails/c.jpg")
        file("DCIM/Camera/photo.jpg")
        file("Download/.thumbnails/d.jpg") // not one of the two known folders
        val result = find()
        assertEquals(
            listOf("DCIM/.thumbnails/a.jpg", "DCIM/.thumbnails/sub/b.jpg", "Pictures/.thumbnails/c.jpg"),
            result.thumbnails.map { it.path.removePrefix(root.path + "/") }.sorted(),
        )
        assertEquals(5L, result.thumbnails.first { it.name == "a.jpg" }.size)
        assertEquals(emptyList<String>(), result.tempFiles.map { it.name })
    }

    @Test fun nomediaMarkersAreNeverJunk() {
        file("DCIM/.thumbnails/a.jpg")
        val marker = file("DCIM/.thumbnails/.nomedia", "")
        file("Pictures/.thumbnails/.nomedia", "")
        file("Download/stuff/.nomedia", "")
        val result = find()
        assertEquals(listOf("a.jpg"), result.thumbnails.map { it.name })
        assertEquals(emptyList<String>(), result.tempFiles.map { it.name })
        assertFalse(isJunkFile(root, marker))
        // A folder holding only a .nomedia marker still counts as having content.
        assertFalse(empties().contains("Download/stuff"))
    }

    @Test fun thumbnailFolderWithFilesIsNotReportedEmpty() {
        file("DCIM/.thumbnails/a.jpg")
        assertEquals(emptyList<String>(), empties())
    }

    @Test fun emptyThumbnailsFolderIsReportedAsEmpty() {
        file("DCIM/Camera/photo.jpg")
        dir("DCIM/.thumbnails")
        assertEquals(listOf("DCIM/.thumbnails"), empties())
    }

    // --- temp files ------------------------------------------------------------------------------

    @Test fun tempNameMatching() {
        for (name in listOf("a.tmp", "a.TMP", "a.temp", "a.Part", "a.crdownload", "A.CRDOWNLOAD", "~\$doc.docx", "Thumbs.db", ".DS_Store")) {
            assertTrue(name, isTempFileName(name))
        }
        for (name in listOf("report.tmp.pdf", "tmp", "atmp", "a.tmpx", "thumbs.db", "Thumbs.db.bak", ".ds_store", "a~\$b.txt", "part", "a.part.txt", "~tmp")) {
            assertFalse(name, isTempFileName(name))
        }
    }

    @Test fun tempFilesAreFoundAnywhereExceptAndroidAndTrash() {
        file("Download/a.tmp")
        file("Documents/deep/er/b.crdownload")
        file("Download/~\$budget.xlsx")
        file("Download/Thumbs.db")
        file("Download/.DS_Store")
        file("Download/report.tmp.pdf")
        file("Android/data/com.example/c.tmp")
        file("Android/media/com.example/d.tmp")
        file(".SweeperTrash/e.tmp")
        file(".SweeperTrash/00000000-0000-0000-0000-000000000000")
        assertEquals(
            listOf(
                "Documents/deep/er/b.crdownload",
                "Download/.DS_Store",
                "Download/Thumbs.db",
                "Download/a.tmp",
                "Download/~\$budget.xlsx",
            ),
            temps().sorted(),
        )
    }

    @Test fun tempFilesInsideHiddenFoldersAreReported() {
        file("Stuff/.hidden/x.part")
        assertEquals(listOf("Stuff/.hidden/x.part"), temps())
    }

    @Test fun tempSymlinkIsNotReported() {
        val target = tmp.newFile("real.bin")
        dir("Download")
        Files.createSymbolicLink(File(root, "Download/link.tmp").toPath(), target.toPath())
        assertEquals(emptyList<String>(), temps())
    }

    @Test fun tempDirectoryNamedLikeATempFileIsNotATempFile() {
        file("Download/folder.tmp/inner.txt")
        assertEquals(emptyList<String>(), temps())
    }

    @Test fun trashContentsAreNeverReported() {
        file(".SweeperTrash/x.tmp")
        file(".SweeperTrash/.thumbnails/y.jpg")
        dir(".SweeperTrash/empty")
        val result = find()
        assertEquals(emptyList<String>(), result.emptyFolders)
        assertEquals(emptyList<String>(), result.tempFiles.map { it.name })
        assertEquals(emptyList<String>(), result.thumbnails.map { it.name })
    }

    @Test fun junkBytesSumsThumbnailsAndTempFiles() {
        file("DCIM/.thumbnails/a.jpg", "123")
        file("Download/a.tmp", "12345")
        dir("Stuff")
        val result = find()
        assertEquals(8L, result.junkBytes)
        assertEquals(now, result.scannedAt)
    }

    @Test fun cancellationStopsTheWalk() {
        file("Download/a.tmp")
        try {
            JunkFinder(root) { now }.find(isCancelled = { true })
            fail("expected a CancellationException")
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test fun isJunkFileRechecksTheRules() {
        val tmpFile = file("Download/a.tmp")
        val normal = file("Download/a.txt")
        val androidTmp = file("Android/media/x/b.tmp")
        val outside = tmp.newFile("c.tmp")
        val link = File(root, "Download/link.tmp")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertTrue(isJunkFile(root, tmpFile))
        assertFalse(isJunkFile(root, normal))
        assertFalse(isJunkFile(root, androidTmp))
        assertFalse(isJunkFile(root, outside))
        assertFalse(isJunkFile(root, link))
        assertFalse(isJunkFile(root, File(root, "Download")))
    }

    // --- EmptyFolderRemover ----------------------------------------------------------------------

    @Test fun removerDeletesAChainDeepestFirst() {
        file("Download/keep.txt")
        dir("Download/a/b/c")
        val reported = find().emptyFolders
        assertEquals(1, reported.size)
        val result = EmptyFolderRemover(root).remove(reported)
        assertEquals(EmptyFolderResult(removed = 1, skipped = 0), result)
        assertFalse(File(root, "Download/a").exists())
        assertTrue(File(root, "Download/keep.txt").exists())
        assertEquals(emptyList<String>(), empties())
    }

    @Test fun removerHandlesNestedFoldersInAnyListOrder() {
        dir("Stuff/a/b")
        val paths = listOf(File(root, "Stuff/a").path, File(root, "Stuff/a/b").path, File(root, "Stuff").path)
        assertEquals(EmptyFolderResult(removed = 3, skipped = 0), EmptyFolderRemover(root).remove(paths))
        assertFalse(File(root, "Stuff").exists())
    }

    @Test fun removerSkipsAFolderThatGotAFileSinceTheScan() {
        dir("Download/a/b/c")
        dir("Download/x/y")
        file("Download/keep.txt")
        val reported = find().emptyFolders
        assertEquals(2, reported.size)
        val late = file("Download/a/b/c/late.txt")
        val result = EmptyFolderRemover(root).remove(reported)
        assertEquals(EmptyFolderResult(removed = 1, skipped = 1), result)
        assertTrue(late.exists())
        assertTrue(File(root, "Download/a/b/c").isDirectory)
        assertFalse(File(root, "Download/x").exists())
    }

    @Test fun removerSkipsAFolderThatGotAHiddenFileOrSymlink() {
        dir("Download/h/sub")
        dir("Download/s")
        val target = tmp.newFile("t.txt")
        val reported = listOf(File(root, "Download/h").path, File(root, "Download/s").path)
        file("Download/h/sub/.hidden", "")
        Files.createSymbolicLink(File(root, "Download/s/link").toPath(), target.toPath())
        assertEquals(EmptyFolderResult(removed = 0, skipped = 2), EmptyFolderRemover(root).remove(reported))
        assertTrue(File(root, "Download/h/sub/.hidden").exists())
        assertTrue(target.exists())
    }

    @Test fun removerRefusesProtectedAndOutsidePaths() {
        dir("Download")
        dir("DCIM")
        dir("Android/media/x")
        dir(".SweeperTrash/inner")
        val outside = tmp.newFolder("outside")
        val paths = listOf(
            root.path,
            File(root, "Download").path,
            File(root, "dcim").path,
            File(root, "DCIM").path,
            File(root, "Android/media/x").path,
            File(root, ".SweeperTrash/inner").path,
            outside.path,
            "relative/path",
        )
        assertEquals(EmptyFolderResult(removed = 0, skipped = paths.size), EmptyFolderRemover(root).remove(paths))
        assertTrue(File(root, "Download").isDirectory)
        assertTrue(File(root, "Android/media/x").isDirectory)
        assertTrue(File(root, ".SweeperTrash/inner").isDirectory)
        assertTrue(outside.isDirectory)
    }

    @Test fun removerDoesNotFollowASymlinkToAnEmptyFolder() {
        val outside = tmp.newFolder("outside")
        dir("Download")
        val link = File(root, "Download/link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertEquals(EmptyFolderResult(removed = 0, skipped = 1), EmptyFolderRemover(root).remove(listOf(link.path)))
        assertTrue(outside.isDirectory)
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test fun removerRefusesAPathReachedThroughASymlinkedParent() {
        val outside = tmp.newFolder("outside")
        File(outside, "empty").mkdirs()
        dir("Download")
        Files.createSymbolicLink(File(root, "Download/link").toPath(), outside.toPath())
        val viaLink = File(root, "Download/link/empty").path
        assertEquals(EmptyFolderResult(removed = 0, skipped = 1), EmptyFolderRemover(root).remove(listOf(viaLink)))
        assertTrue(File(outside, "empty").isDirectory)
    }

    @Test fun removerSkipsAPathThatIsAFile() {
        val f = file("Download/a.txt")
        assertEquals(EmptyFolderResult(removed = 0, skipped = 1), EmptyFolderRemover(root).remove(listOf(f.path)))
        assertTrue(f.exists())
    }
}
