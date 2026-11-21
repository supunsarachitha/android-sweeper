package com.supun.sweeper

import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supun.sweeper.files.Trash
import com.supun.sweeper.storage.storageRoot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DuplicatesTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val aName = "sweeper-dup-a.bin"
    private val bName = "sweeper-dup-b.bin"
    private val cName = "sweeper-dup-c.bin"
    private val aPath = "/sdcard/Download/$aName"
    private val bPath = "/sdcard/Documents/$bName"
    private val cPath = "/sdcard/Download/$cName"

    /** Runs a shell command and reads its output to the end, so the command has finished. */
    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }

    private fun exists(path: String): Boolean = shell("ls $path 2>/dev/null").trim() == path

    private fun rowsWithPrefix(prefix: String) = rule.onAllNodes(
        SemanticsMatcher("test tag starts with $prefix") {
            SemanticsProperties.TestTag in it.config && it.config[SemanticsProperties.TestTag].startsWith(prefix)
        },
    )

    private fun rowCount(fileName: String): Int = rowsWithPrefix("dup_row_${fileName}_").fetchSemanticsNodes().size

    @Before fun setUp() {
        shell("appops set --uid com.supun.sweeper MANAGE_EXTERNAL_STORAGE allow")
        shell("mkdir -p /sdcard/Documents")
        shell("dd if=/dev/urandom of=$aPath bs=1024 count=200")
        shell("cp $aPath $bPath")
        shell("dd if=/dev/urandom of=$cPath bs=1024 count=200")
        assertTrue(exists(aPath) && exists(bPath) && exists(cPath))
        // Restart the activity so the screens read the new permission state.
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
    }

    private val capture = OpenCapture()

    @After fun cleanUp() {
        capture.stop()
        shell("rm -f $aPath $bPath $cPath")
        try {
            Trash(storageRoot()).emptyAll()
        } catch (e: Exception) {
            // Best effort; the emulator is thrown away after the run.
        }
    }

    @Test fun findsOneGroupAndTrashesOneCopy() {
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("duplicates_card"))
        rule.onNodeWithTag("duplicates_card").assertIsDisplayed()
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("duplicates_card"))
        rule.onNodeWithTag("duplicates_card").performClick()
        rule.waitUntil(120_000) {
            rule.onAllNodesWithTag("keep_oldest_all_button").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitUntil(30_000) { rowCount(aName) == 1 && rowCount(bName) == 1 }
        assertEquals(1, rowCount(aName))
        assertEquals(1, rowCount(bName))
        assertEquals("same size but different content must not be listed", 0, rowCount(cName))

        rule.onNodeWithTag("keep_oldest_all_button").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("move_to_trash_button").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("move_to_trash_button").performClick()
        rule.onNodeWithTag("confirm_button").performClick()
        rule.waitUntil(60_000) { rowCount(aName) == 0 && rowCount(bName) == 0 }

        val aLeft = exists(aPath)
        val bLeft = exists(bPath)
        assertTrue("exactly one copy must remain (a=$aLeft b=$bLeft)", aLeft != bLeft)
        assertTrue("the different file must be untouched", exists(cPath))
        val trashed = Trash(storageRoot()).list().map { it.name }
        val gone = if (aLeft) bName else aName
        assertTrue("the removed copy must be in the trash: $trashed", gone in trashed)
        assertEquals(1, trashed.count { it == aName || it == bName })
    }

    @Test fun rowTapOpensThePreviewAndKeepOneStillHolds() {
        capture.start()
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("duplicates_card"))
        rule.onNodeWithTag("duplicates_card").performClick()
        rule.waitUntil(120_000) {
            rule.onAllNodesWithTag("keep_oldest_all_button").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitUntil(30_000) { rowCount(aName) == 1 && rowCount(bName) == 1 }
        rule.onNodeWithTag("selection_summary").assertTextContains("0 selected", substring = true)

        // Tapping a row opens the preview in the app and selects nothing.
        rowsWithPrefix("dup_row_${aName}_").onFirst().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("preview_screen").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, capture.intents.size)
        rule.onNodeWithTag("preview_select_button").assertTextContains("Select")
        // Select in the preview selects this copy, like its checkbox would.
        rule.onNodeWithTag("preview_select_button").performClick()
        rule.onNodeWithTag("preview_select_button").assertTextContains("Deselect")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("preview_screen").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("selection_summary").assertTextContains("1 selected", substring = true)
        rowsWithPrefix("dup_check_${aName}_").onFirst().assertIsEnabled()
        rowsWithPrefix("dup_check_${bName}_").onFirst().assertIsNotEnabled()

        // The twin that has to stay can be previewed, but has no Select and no Move to trash.
        rowsWithPrefix("dup_row_${bName}_").onFirst().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("preview_screen").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("preview_keep_note").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("preview_select_button").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag("preview_trash_button").fetchSemanticsNodes().isEmpty())
        // Open with... still works for it, and changes no selection.
        rule.onNodeWithTag("preview_open_button").performClick()
        rule.waitUntil(10_000) { capture.intents.size == 1 }
        assertEquals(Intent.ACTION_VIEW, capture.viewIntent(0).action)
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("preview_screen").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("selection_summary").assertTextContains("1 selected", substring = true)
    }
}
