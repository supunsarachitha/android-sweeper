package com.supun.sweeper

import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
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

    @After fun cleanUp() {
        shell("rm -f $aPath $bPath $cPath")
        try {
            Trash(storageRoot()).emptyAll()
        } catch (e: Exception) {
            // Best effort; the emulator is thrown away after the run.
        }
    }

    @Test fun findsOneGroupAndTrashesOneCopy() {
        rule.onNodeWithTag("duplicates_card").assertIsDisplayed()
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
}
