package com.supun.sweeper.files

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenRulesTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var guard: PathGuard

    @Before fun setUp() {
        root = tmp.newFolder("storage").canonicalFile
        guard = PathGuard(root)
    }

    private fun file(path: String): File {
        val f = File(root, path)
        f.parentFile!!.mkdirs()
        f.writeText("x")
        return f
    }

    @Test fun extensionIsLowerCaseAndEmptyWhenMissing() {
        assertEquals("pdf", OpenRules.extensionOf("Report.PDF"))
        assertEquals("gz", OpenRules.extensionOf("a.tar.gz"))
        assertEquals("", OpenRules.extensionOf("README"))
        assertEquals("", OpenRules.extensionOf(".hidden"))
        assertEquals("", OpenRules.extensionOf("trailing."))
    }

    @Test fun apkIsDetectedByExtensionIgnoringCase() {
        assertTrue(OpenRules.isApk("app.apk"))
        assertTrue(OpenRules.isApk("APP.APK"))
        assertFalse(OpenRules.isApk("app.apk.txt"))
        assertFalse(OpenRules.isApk("apk"))
    }

    @Test fun mimeUsesFixedApkThenSystemThenTableThenFallback() {
        assertEquals(APK_MIME, OpenRules.mimeType("a.apk") { "text/wrong" })
        assertEquals("image/x-system", OpenRules.mimeType("a.png") { "image/x-system" })
        assertEquals("image/png", OpenRules.mimeType("a.png"))
        assertEquals("application/pdf", OpenRules.mimeType("a.PDF") { null })
        assertEquals(UNKNOWN_MIME, OpenRules.mimeType("a.unknownext"))
        assertEquals(UNKNOWN_MIME, OpenRules.mimeType("noextension"))
        assertEquals("video/mp4", OpenRules.mimeType("a.mp4") { "" })
    }

    @Test fun regularFileInsideRootIsAllowed() {
        val f = file("Download/doc.pdf")
        val check = OpenRules.check(f, guard)
        assertEquals(OpenCheck.Allowed(f.canonicalFile), check)
    }

    @Test fun missingFileIsReportedAsMissing() {
        assertEquals(OpenCheck.Missing, OpenRules.check(File(root, "Download/gone.pdf"), guard))
    }

    @Test fun trashAndAppDataFoldersAreRefused() {
        assertEquals(OpenCheck.NotAllowed, OpenRules.check(file("$TRASH_DIR_NAME/abc"), guard))
        assertEquals(OpenCheck.NotAllowed, OpenRules.check(file("Android/data/com.x/f.txt"), guard))
        assertEquals(OpenCheck.NotAllowed, OpenRules.check(file("Android/obb/com.x/main.obb"), guard))
    }

    @Test fun fileOutsideTheRootIsRefused() {
        val outside = tmp.newFile("outside.txt")
        assertEquals(OpenCheck.NotAllowed, OpenRules.check(outside, guard))
    }

    @Test fun symlinkIsRefused() {
        val target = file("Download/real.txt")
        val link = File(root, "Download/link.txt")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        assertEquals(OpenCheck.NotAllowed, OpenRules.check(link, guard))
    }

    @Test fun directoryIsRefused() {
        val dir = File(root, "Download/folder").also { it.mkdirs() }
        assertEquals(OpenCheck.NotAllowed, OpenRules.check(dir, guard))
    }

    @Test fun resultMessagesAreSetOnlyWhenNothingStarted() {
        assertEquals(null, OpenResult.Started.message)
        assertEquals("No app on this phone can open this file", OpenResult.NoApp.message)
        assertEquals("This file no longer exists", OpenResult.Missing.message)
    }
}
