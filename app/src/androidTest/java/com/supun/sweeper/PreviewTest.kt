package com.supun.sweeper

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The preview screen for each kind of file, and that a damaged file never crashes it. */
@RunWith(AndroidJUnit4::class)
class PreviewTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val dir = File("/sdcard/Download")
    private val oldTime = 1_735_689_600_000L // 1 Jan 2025: old enough for the "Old" tab
    private val capture = OpenCapture()
    private val made = mutableListOf<File>()

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }

    private fun make(name: String, bytes: ByteArray): String {
        val f = File(dir, name)
        f.writeBytes(bytes)
        f.setLastModified(oldTime)
        made.add(f)
        return name
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 60_000) {
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForNoTag(tag: String, timeoutMs: Long = 60_000) {
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    }

    @Before fun setUp() {
        shell("appops set --uid com.supun.sweeper MANAGE_EXTERNAL_STORAGE allow")
    }

    @After fun cleanUp() {
        capture.stop()
        made.forEach { it.delete() }
    }

    private fun pngBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(30, 120, 200))
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun pdfBytes(): ByteArray {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create())
        page.canvas.drawText("Hello PDF", 40f, 80f, Paint().apply { textSize = 24f })
        doc.finishPage(page)
        val out = java.io.ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    private fun openOldTab(firstRow: String) {
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        capture.start()
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("files_card"))
        rule.onNodeWithTag("files_card").performClick()
        waitForTag("files_tab_old")
        rule.onNodeWithTag("files_tab_old").performClick()
        waitForTag("file_row_$firstRow")
    }

    private fun openPreviewOf(name: String, expectedTag: String) {
        rule.onNodeWithTag("files_list").performScrollToNode(hasTestTag("file_row_$name"))
        rule.onNodeWithTag("file_row_$name").performClick()
        waitForTag("preview_screen")
        waitForTag(expectedTag)
        rule.onNodeWithTag("preview_details").assertIsDisplayed()
    }

    private fun closePreview() {
        rule.onNodeWithContentDescription("Back").performClick()
        waitForNoTag("preview_screen")
    }

    @Test fun eachKindOfFileGetsItsPreview() {
        val image = make("sweeper-prev-image.png", pngBytes())
        val text = make("sweeper-prev-notes.txt", "hello preview\nsecond line\n".toByteArray())
        val pdf = make("sweeper-prev-doc.pdf", pdfBytes())
        val zip = make("sweeper-prev-archive.zip", ByteArray(2048) { (it * 7).toByte() })
        val apk = make(
            "sweeper-prev-app.apk",
            File(InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.sourceDir).readBytes(),
        )
        openOldTab(image)

        openPreviewOf(image, "preview_image")
        closePreview()

        openPreviewOf(text, "preview_text")
        rule.onNodeWithText("hello preview", substring = true).assertIsDisplayed()
        closePreview()

        openPreviewOf(pdf, "preview_pdf")
        rule.onNodeWithText("1 page, first page shown").assertIsDisplayed()
        closePreview()

        openPreviewOf(zip, "preview_details_only")
        rule.onNodeWithTag("preview_open_button").assertIsDisplayed()
        closePreview()

        openPreviewOf(apk, "preview_apk")
        rule.onNodeWithText("Android Sweeper").assertIsDisplayed()
        rule.onNodeWithText("com.supun.sweeper").assertIsDisplayed()
        rule.onNodeWithTag("preview_install_button").assertIsDisplayed()
        closePreview()

        assertEquals("previewing starts no other app", 0, capture.intents.size)
    }

    @Test fun damagedAndOversizedFilesNeverCrashThePreview() {
        val badPng = make("sweeper-prev-bad.png", ByteArray(500) { (it * 31 + 7).toByte() })
        val badPdf = make("sweeper-prev-bad.pdf", "%PDF-1.4 not really".toByteArray())
        val binaryTxt = make("sweeper-prev-binary.txt", ByteArray(300) { (it % 5).toByte() })
        val longTxt = make("sweeper-prev-long.txt", ByteArray(200_000) { if (it % 80 == 79) '\n'.code.toByte() else 'x'.code.toByte() })
        openOldTab(badPng)

        openPreviewOf(badPng, "preview_failed")
        rule.onNodeWithText("Can't preview this file").assertIsDisplayed()
        closePreview()

        openPreviewOf(badPdf, "preview_failed")
        closePreview()

        openPreviewOf(binaryTxt, "preview_failed")
        closePreview()

        openPreviewOf(longTxt, "preview_text")
        rule.onNodeWithText("Showing the first 64 KB").assertIsDisplayed()
        closePreview()
        assertTrue(rule.onAllNodesWithTag("preview_screen").fetchSemanticsNodes().isEmpty())
    }
}
