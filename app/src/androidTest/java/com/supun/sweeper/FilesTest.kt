package com.supun.sweeper

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supun.sweeper.files.Trash
import com.supun.sweeper.storage.storageRoot
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilesTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val testFile = "sweeper-test-big.bin"
    private val testPath = "/sdcard/Download/$testFile"

    /** Runs a shell command and reads its output to the end, so the command has finished. */
    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }

    private fun downloadExists(): Boolean = shell("ls /sdcard/Download").lines().any { it.trim() == testFile }

    private fun waitForTag(tag: String, timeoutMs: Long = 60_000) {
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForNoTag(tag: String, timeoutMs: Long = 60_000) {
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    }

    @Before fun setUp() {
        shell("appops set --uid com.supun.sweeper MANAGE_EXTERNAL_STORAGE allow")
        shell("dd if=/dev/zero of=$testPath bs=1048576 count=60")
        assertTrue(downloadExists())
        // Restart the activity so the screens read the new permission state.
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
    }

    @After fun cleanUp() {
        shell("rm -f $testPath")
        // Empty the trash through the app's own code, while All files access is still granted.
        try {
            Trash(storageRoot()).emptyAll()
        } catch (e: Exception) {
            // Best effort; the emulator is thrown away after the run.
        }
        // The grant stays: revoking it could restart the app process mid-run. The smoke script revokes it.
    }

    @Test fun homeShowsFilesAndTrashCards() {
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("files_card"))
        rule.onNodeWithTag("files_card").assertIsDisplayed()
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("trash_card"))
        rule.onNodeWithTag("trash_card").assertIsDisplayed()
    }

    @Test fun trashAndRestoreALargeFile() {
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("files_card"))
        rule.onNodeWithTag("files_card").performClick()
        waitForTag("file_row_$testFile")
        rule.onNodeWithTag("file_row_$testFile").assertIsDisplayed()

        rule.onNodeWithTag("file_row_$testFile").performClick()
        rule.onNodeWithTag("move_to_trash_button").performClick()
        rule.onNodeWithTag("confirm_button").performClick()
        waitForNoTag("file_row_$testFile")
        assertFalse("file should have left Download", downloadExists())

        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("trash_card"))
        rule.onNodeWithTag("trash_card").performClick()
        waitForTag("trash_row_$testFile")
        rule.onNodeWithTag("trash_row_$testFile").assertIsDisplayed()

        rule.onNodeWithTag("restore_button_$testFile").performClick()
        waitForNoTag("trash_row_$testFile")
        assertTrue("file should be back in Download", downloadExists())
    }
}
