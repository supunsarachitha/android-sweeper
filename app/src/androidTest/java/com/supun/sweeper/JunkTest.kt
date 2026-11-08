package com.supun.sweeper

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supun.sweeper.files.Trash
import com.supun.sweeper.storage.storageRoot
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JunkTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val emptyDir = "/sdcard/Download/sweeper-junk-empty"
    private val tmpPath = "/sdcard/Download/sweeper-junk.tmp"
    private val thumbDir = "/sdcard/DCIM/.thumbnails"
    private val thumbPath = "$thumbDir/sweeper-thumb.jpg"

    /** Runs a shell command and reads its output to the end, so the command has finished. */
    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }

    private fun exists(path: String): Boolean = shell("ls -d $path 2>/dev/null").trim() == path

    private fun hasNode(tag: String): Boolean = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** Scrolls the junk list to the node with [tag] (a section header or row). */
    private fun scrollJunkList(tag: String) {
        rule.onNodeWithTag("junk_list").performScrollToNode(hasTestTag(tag))
    }

    /** Scrolls to a row; if its section is closed (long lists start closed), opens it first. */
    private fun showRow(expandTag: String, rowTag: String) {
        try {
            scrollJunkList(rowTag)
        } catch (e: AssertionError) {
            scrollJunkList(expandTag)
            rule.onNodeWithTag(expandTag).performClick()
            rule.waitForIdle()
            scrollJunkList(rowTag)
        }
    }

    @Before fun setUp() {
        shell("appops set --uid com.supun.sweeper MANAGE_EXTERNAL_STORAGE allow")
        shell("mkdir -p $emptyDir/a/b")
        shell("echo junk > $tmpPath")
        shell("mkdir -p $thumbDir")
        shell("echo thumb > $thumbPath")
        assertTrue(exists("$emptyDir/a/b") && exists(tmpPath) && exists(thumbPath))
        // Restart the activity so the screens read the new permission state.
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
    }

    @After fun cleanUp() {
        shell("rm -f $tmpPath $thumbPath")
        shell("rmdir $emptyDir/a/b $emptyDir/a $emptyDir") // dirs only, so rmdir is enough
        shell("rmdir $thumbDir") // only if nothing else is in it
        try {
            Trash(storageRoot()).emptyAll()
        } catch (e: Exception) {
            // Best effort; the emulator is thrown away after the run.
        }
    }

    @Test fun cleansEmptyFolderTempFileAndThumbnail() {
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("junk_card"))
        rule.onNodeWithTag("junk_card").performClick()
        rule.waitUntil(120_000) { hasNode("junk_section_empty") }

        showRow("junk_expand_empty", "junk_row_sweeper-junk-empty")
        showRow("junk_expand_temp", "junk_row_sweeper-junk.tmp")
        showRow("junk_expand_thumbs", "junk_row_sweeper-thumb.jpg")

        for (header in listOf("junk_section_empty", "junk_section_thumbs", "junk_section_temp")) {
            scrollJunkList(header)
            rule.onNodeWithTag(header).performClick()
        }
        rule.onNodeWithTag("clean_button").performClick()
        rule.onNodeWithTag("confirm_button").performClick()

        rule.waitUntil(60_000) { !exists(emptyDir) && !exists(tmpPath) && !exists(thumbPath) }
        assertTrue("the empty folder must be gone", !exists(emptyDir))
        assertTrue("Download itself must stay", exists("/sdcard/Download"))
        assertTrue("DCIM itself must stay", exists("/sdcard/DCIM"))
        val trashed = Trash(storageRoot()).list().map { it.name }
        assertTrue("the temp file must be in the trash: $trashed", "sweeper-junk.tmp" in trashed)
        assertTrue("the thumbnail must be in the trash: $trashed", "sweeper-thumb.jpg" in trashed)
    }
}
