package com.supun.sweeper

import android.os.ParcelFileDescriptor
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
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
import com.supun.sweeper.files.Trash
import com.supun.sweeper.storage.storageRoot
import org.junit.After
import org.junit.Assert.assertEquals
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
    private val apkFile = "sweeper-test-open.apk"
    private val apkPath = "/sdcard/Download/$apkFile"
    private val capture = OpenCapture()

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
        capture.stop()
        shell("rm -f $testPath $apkPath")
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

        rule.onNodeWithTag("file_check_$testFile").performClick()
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

    private fun openFilesScreen() {
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("files_card"))
        rule.onNodeWithTag("files_card").performClick()
        waitForTag("file_row_$testFile")
    }

    @Test fun rowTapOpensTheFileAndOnlyTheCheckboxSelects() {
        capture.start()
        openFilesScreen()
        rule.onNodeWithTag("selection_summary").assertTextContains("0 selected", substring = true)

        rule.onNodeWithTag("file_row_$testFile").performClick()
        rule.waitUntil(10_000) { capture.intents.size == 1 }
        rule.onNodeWithTag("selection_summary").assertTextContains("0 selected", substring = true)
        val view = capture.viewIntent(0)
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals("content", view.data?.scheme)
        assertEquals("com.supun.sweeper.fileprovider", view.data?.authority)
        assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("never write access", 0, view.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(Intent.ACTION_CHOOSER, capture.intents[0].action)

        rule.onNodeWithTag("file_check_$testFile").performClick()
        rule.onNodeWithTag("selection_summary").assertTextContains("1 selected", substring = true)
        assertEquals("the checkbox must not open the file", 1, capture.intents.size)
        rule.onNodeWithTag("file_check_$testFile").performClick()
        rule.onNodeWithTag("selection_summary").assertTextContains("0 selected", substring = true)
    }

    @Test fun apkRowAsksBeforeOpeningTheInstaller() {
        shell("dd if=/dev/zero of=$apkPath bs=1024 count=64")
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        capture.start()
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("files_card"))
        rule.onNodeWithTag("files_card").performClick()
        waitForTag("files_tab_apk")
        rule.onNodeWithTag("files_tab_apk").performClick()
        waitForTag("file_row_$apkFile")

        rule.onNodeWithTag("file_row_$apkFile").performClick()
        waitForTag("install_dialog")
        rule.onNodeWithText("Install this app?").assertIsDisplayed()
        rule.onNodeWithText("Only install apps you trust.").assertIsDisplayed()
        rule.onNodeWithTag("install_cancel_button").performClick()
        waitForNoTag("install_dialog")
        assertEquals("cancel must not start the installer", 0, capture.intents.size)
        rule.onNodeWithTag("selection_summary").assertTextContains("0 selected", substring = true)

        rule.onNodeWithTag("file_row_$apkFile").performClick()
        waitForTag("install_dialog")
        rule.onNodeWithTag("install_confirm_button").performClick()
        rule.waitUntil(10_000) { capture.intents.size == 1 }
        val view = capture.viewIntent(0)
        assertEquals("application/vnd.android.package-archive", view.type)
        assertEquals("content", view.data?.scheme)
    }
}
