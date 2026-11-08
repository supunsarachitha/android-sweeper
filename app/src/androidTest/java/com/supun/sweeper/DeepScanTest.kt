package com.supun.sweeper

import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supun.sweeper.files.Trash
import com.supun.sweeper.malware.InstallResult
import com.supun.sweeper.malware.SignatureStore
import com.supun.sweeper.malware.SignatureTestKit
import com.supun.sweeper.malware.SignatureVerifier
import com.supun.sweeper.storage.MalwareSignatures
import com.supun.sweeper.storage.storageRoot
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The deep scan finds a random file standing in for a Windows program in a hidden folder. Its hash is in a test
 * "all files" list that is signed with a key made by this test and checked with that same key (the real app only
 * trusts its built-in key). The list is fresh, so nothing is downloaded, and no network is used.
 */
@RunWith(AndroidJUnit4::class)
class DeepScanTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val fileName = "sweeper-deep-test.exe"
    private val folder = "/sdcard/Documents/.hidden"
    private val path = "$folder/$fileName"
    private var store: SignatureStore? = null

    /** Runs a shell command and reads its output to the end, so the command has finished. */
    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }

    private fun fileExists(): Boolean = shell("ls -A $folder").lines().any { it.trim() == fileName }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun sha256OfTestFile(): String =
        try {
            hex(MessageDigest.getInstance("SHA-256").digest(File(path).readBytes()))
        } catch (e: Exception) {
            shell("sha256sum $path").trim().substringBefore(' ')
        }

    private fun hasNode(tag: String): Boolean = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun waitAndScrollTo(tag: String, timeoutMs: Long) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            try {
                rule.onNodeWithTag("malware_list").performScrollToNode(hasTestTag(tag))
                return
            } catch (e: AssertionError) {
                if (System.currentTimeMillis() > end) throw e
                Thread.sleep(500)
            }
        }
    }

    private fun installAllList(entries: List<Pair<ByteArray, String>>) {
        val kit = SignatureTestKit()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "signatures-all-test")
        val testStore = SignatureStore(
            dir,
            verifier = SignatureVerifier(kit.publicKeyBase64),
            maxDataBytes = SignatureStore.MAX_ALL_DATA_BYTES,
        )
        store = testStore
        testStore.clear()
        val (data, sig) = kit.signedFile(System.currentTimeMillis() / 1000, entries)
        assertTrue(testStore.install(data, sig) is InstallResult.Updated)
        MalwareSignatures.useAllStoreForTests(testStore)
        // Restart the activity so the screens use the new store and read the permission state.
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
    }

    private fun openMalwareScreen() {
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("malware_card"))
        rule.onNodeWithTag("malware_card").performClick()
        waitAndScrollTo("deep_scan_card", 60_000)
    }

    @After fun cleanUp() {
        shell("rm -f $path")
        shell("rmdir $folder")
        try {
            Trash(storageRoot()).emptyAll()
        } catch (e: Exception) {
            // Best effort; the emulator is thrown away after the run.
        }
        store?.clear()
        MalwareSignatures.useAllStoreForTests(null)
    }

    @Test fun deepScanFindsAWindowsProgramInAHiddenFolderAndMovesItToTheTrash() {
        shell("appops set --uid com.supun.sweeper MANAGE_EXTERNAL_STORAGE allow")
        shell("mkdir -p $folder")
        shell("dd if=/dev/urandom of=$path bs=1024 count=64") // no shell redirects: the command may not run through sh
        assertTrue("test file was not created", fileExists())
        installAllList(
            listOf(
                SignatureTestKit.hexToBytes(sha256OfTestFile()) to "TestTrojan (exe)",
                MessageDigest.getInstance("SHA-256").digest("some other sample".toByteArray()) to "OtherFamily (pdf)",
            ),
        )

        openMalwareScreen()
        rule.onNodeWithTag("deep_scan_card").assertIsDisplayed()
        rule.onNodeWithTag("deep_list_info").assertIsDisplayed()

        rule.onNodeWithTag("deep_scan_button").performClick()
        waitAndScrollTo("deep_match_$fileName", 240_000)
        rule.onNodeWithTag("deep_match_$fileName").assertIsDisplayed()
        rule.onNodeWithText("TestTrojan (exe)").assertIsDisplayed()
        rule.onNodeWithText("Can't run on Android, but it is dangerous on a computer. Don't copy or open it there.")
            .assertIsDisplayed()

        rule.onNodeWithTag("deep_trash_$fileName").performClick()
        rule.onNodeWithTag("confirm_button").performClick()
        rule.waitUntil(60_000) { !hasNode("deep_match_$fileName") }
        assertFalse("file should have left the hidden folder", fileExists())
        assertTrue("file should be in the trash", Trash(storageRoot()).list().any { it.name == fileName })
    }

    @Test fun deepScanCardOffersADownloadWhenNoListIsStored() {
        shell("appops set --uid com.supun.sweeper MANAGE_EXTERNAL_STORAGE allow")
        val empty = SignatureStore(
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "signatures-all-empty"),
            maxDataBytes = SignatureStore.MAX_ALL_DATA_BYTES,
        )
        empty.clear()
        store = empty
        MalwareSignatures.useAllStoreForTests(empty)
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()

        openMalwareScreen()
        rule.onNodeWithTag("deep_scan_card").assertIsDisplayed()
        rule.onNodeWithTag("deep_list_missing").assertIsDisplayed()
        rule.onNodeWithTag("deep_scan_button").assertIsDisplayed()
        rule.onNodeWithTag("update_deep_list_button").assertIsDisplayed()
        // Large files are skipped by default; the switch includes them.
        rule.onNodeWithTag("malware_list").performScrollToNode(hasTestTag("deep_include_large"))
        rule.onNodeWithTag("deep_include_large").assertIsOff()
        rule.onNodeWithTag("deep_include_large").performClick()
        rule.onNodeWithTag("deep_include_large").assertIsOn()
    }
}
