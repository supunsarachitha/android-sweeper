package com.supun.sweeper

import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supun.sweeper.files.Trash
import com.supun.sweeper.malware.DeepListStore
import com.supun.sweeper.malware.DeepListTestSource
import com.supun.sweeper.malware.InstallResult
import com.supun.sweeper.malware.SignatureStore
import com.supun.sweeper.malware.SignatureTestKit
import com.supun.sweeper.malware.SignatureVerifier
import com.supun.sweeper.scan.ScanFrequency
import com.supun.sweeper.scan.ScheduledNetwork
import com.supun.sweeper.scan.ScheduledScan
import com.supun.sweeper.scan.ScheduledScanNotifier
import com.supun.sweeper.scan.ScheduledScanStore
import com.supun.sweeper.storage.MalwareSignatures
import com.supun.sweeper.storage.storageRoot
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The schedule card and a forced run of the scheduled job. Test lists are signed with a key made here (the real
 * app only trusts its built-in key) and no network is used: the run is told it is not on Wi-Fi.
 */
@RunWith(AndroidJUnit4::class)
class ScheduledScanTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scheduler: JobScheduler = context.getSystemService(JobScheduler::class.java)
    private val notifications: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val settings = ScheduledScanStore(context)

    private val apkName = "sweeper-scheduled-test.apk"
    private val apkPath = "/sdcard/Download/$apkName"
    private val exeFolder = "/sdcard/Documents/.scheduled-hidden"
    private val exeName = "sweeper-scheduled-test.exe"
    private val exePath = "$exeFolder/$exeName"
    private val day = 24L * 60 * 60 * 1000
    private var quickStore: SignatureStore? = null
    private var allStore: DeepListStore? = null

    /** Runs a shell command and reads its output to the end, so the command has finished. */
    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun sha256Of(path: String): ByteArray =
        try {
            MessageDigest.getInstance("SHA-256").digest(File(path).readBytes())
        } catch (e: Exception) {
            SignatureTestKit.hexToBytes(shell("sha256sum $path").trim().substringBefore(' '))
        }

    private fun hasNode(tag: String): Boolean = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun pendingJob(): JobInfo? = scheduler.getPendingJob(ScheduledScan.JOB_ID)

    private fun openMalwareScreen() {
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("malware_card"))
        rule.onNodeWithTag("malware_card").performClick()
        rule.waitUntil(30_000) { hasNode("schedule_card") }
    }

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

    private fun installLists() {
        val kit = SignatureTestKit()
        val filesDir = context.filesDir
        val quick = SignatureStore(File(filesDir, "signatures-sched-test"), verifier = SignatureVerifier(kit.publicKeyBase64))
        quick.clear()
        val (qData, qSig) = kit.signedFile(
            System.currentTimeMillis() / 1000,
            listOf(
                sha256Of(apkPath) to "SchedQuick (apk)",
                MessageDigest.getInstance("SHA-256").digest("some other sample".toByteArray()) to "OtherFamily (apk)",
            ),
        )
        assertTrue(quick.install(qData, qSig) is InstallResult.Updated)
        quickStore = quick
        MalwareSignatures.useStoreForTests(quick)

        val all = DeepListStore(File(filesDir, "signatures-all-sched-test"), verifier = SignatureVerifier(kit.publicKeyBase64))
        all.clear()
        val (aData, aSig) = kit.signedFile(
            System.currentTimeMillis() / 1000,
            listOf(
                sha256Of(exePath) to "SchedTrojan (exe)",
                MessageDigest.getInstance("SHA-256").digest("another sample".toByteArray()) to "OtherFamily (pdf)",
            ),
        )
        assertTrue(all.installBase(aData, aSig) is InstallResult.Updated)
        allStore = all
        MalwareSignatures.useAllStoreForTests(all)
        MalwareSignatures.useDeepSourceForTests(DeepListTestSource(aSig))
    }

    @Before fun setUp() {
        shell("appops set --uid com.supun.sweeper MANAGE_EXTERNAL_STORAGE allow")
        // Without this the card would ask for the permission on the first tap and a system dialog would open.
        shell("pm grant com.supun.sweeper android.permission.POST_NOTIFICATIONS")
        ScheduledScan.apply(context, ScanFrequency.OFF)
        settings.clearAll()
        notifications.cancelAll()
        ScheduledNetwork.overrideForTests(false) // never download in a test
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
    }

    @After fun cleanUp() {
        ScheduledScan.apply(context, ScanFrequency.OFF)
        settings.clearAll()
        notifications.cancelAll()
        ScheduledNetwork.overrideForTests(null)
        shell("rm -f $apkPath $exePath")
        shell("rmdir $exeFolder")
        try {
            Trash(storageRoot()).emptyAll()
        } catch (e: Exception) {
            // Best effort; the emulator is thrown away after the run.
        }
        quickStore?.clear()
        allStore?.clear()
        MalwareSignatures.useStoreForTests(null)
        MalwareSignatures.useAllStoreForTests(null)
        MalwareSignatures.useDeepSourceForTests(null)
        // POST_NOTIFICATIONS stays granted: revoking a runtime permission kills the app's process, and with it
        // this test run. The app is uninstalled when the instrumented run ends, which resets it.
    }

    @Test fun scheduleCardIsVisibleAndStartsOff() {
        openMalwareScreen()
        rule.onNodeWithTag("schedule_card").assertIsDisplayed()
        rule.onNodeWithTag("schedule_off").assertIsDisplayed()
        rule.onNodeWithTag("schedule_daily").assertIsDisplayed()
        rule.onNodeWithTag("schedule_weekly").assertIsDisplayed()
        rule.onNodeWithTag("schedule_note")
            .assertTextContains("Runs while the phone is charging. Updates the malware list first on Wi-Fi.")
        rule.waitUntil(10_000) {
            runCatching { rule.onNodeWithTag("schedule_last_run").assertTextContains("Not run yet") }.isSuccess
        }
        assertNull("nothing is scheduled by default", pendingJob())
    }

    @Test fun dailyWeeklyAndOffChangeThePlatformJob() {
        openMalwareScreen()

        rule.onNodeWithTag("schedule_daily").performClick()
        rule.waitForIdle()
        val daily = pendingJob()!!
        assertTrue(daily.isPeriodic)
        assertEquals(day, daily.intervalMillis)
        assertTrue("runs only while charging", daily.isRequireCharging)
        assertTrue("survives a restart", daily.isPersisted)
        assertEquals("no network constraint", JobInfo.NETWORK_TYPE_NONE, daily.networkType)
        assertEquals(ScanFrequency.DAILY, settings.frequency())

        rule.onNodeWithTag("schedule_weekly").performClick()
        rule.waitForIdle()
        val weekly = pendingJob()!!
        assertTrue(weekly.isPeriodic)
        assertEquals(7 * day, weekly.intervalMillis)
        assertTrue(weekly.isRequireCharging)
        assertEquals(ScanFrequency.WEEKLY, settings.frequency())

        rule.onNodeWithTag("schedule_off").performClick()
        rule.waitForIdle()
        assertNull("Off cancels the job", pendingJob())
        assertEquals(ScanFrequency.OFF, settings.frequency())
    }

    @Test fun aForcedRunStoresItsResultNotifiesAndLeavesTheFindingsForReview() {
        shell("mkdir -p $exeFolder")
        shell("dd if=/dev/urandom of=$apkPath bs=1024 count=64")
        shell("dd if=/dev/urandom of=$exePath bs=1024 count=64")
        assertTrue("test file was not created", shell("ls /sdcard/Download").contains(apkName))
        installLists()
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        openMalwareScreen()

        rule.onNodeWithTag("schedule_daily").performClick()
        rule.waitForIdle()
        assertNotNull(pendingJob())

        // Run it now, as the system would while charging. The descriptor is read to the end so the command finishes.
        shell("cmd jobscheduler run -f com.supun.sweeper ${ScheduledScan.JOB_ID}")
        val end = System.currentTimeMillis() + 180_000
        while (settings.loadResult() == null) {
            check(System.currentTimeMillis() < end) { "the scheduled run did not finish" }
            Thread.sleep(500)
        }
        val result = settings.loadResult()!!
        assertEquals(2, result.findings)

        // The card shows the last run (the screen was open while the job ran).
        rule.waitUntil(30_000) {
            runCatching { rule.onNodeWithTag("schedule_last_run").assertTextContains("2 items found", substring = true) }.isSuccess
        }
        rule.onNodeWithTag("schedule_last_run").assertTextContains("Last run:", substring = true)

        // A notification was posted: counts only, no names.
        var posted: Notification? = null
        val noteEnd = System.currentTimeMillis() + 30_000
        while (posted == null && System.currentTimeMillis() < noteEnd) {
            posted = notifications.activeNotifications.firstOrNull { it.id == ScheduledScanNotifier.NOTIFICATION_ID }?.notification
            if (posted == null) Thread.sleep(300)
        }
        assertNotNull("no notification was posted", posted)
        assertEquals("Scheduled scan: 2 items found", posted!!.extras.getString(Notification.EXTRA_TITLE))
        val body = posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(body, body.startsWith("Tap to review."))
        assertFalse(body.contains(apkName) || body.contains(exeName) || body.contains("Sched"))
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)

        // The hash cache was written, and nothing was removed.
        assertTrue(settings.hashCacheFile.isFile)
        assertTrue("the run must not remove anything", shell("ls /sdcard/Download").contains(apkName))
        assertTrue(shell("ls -A $exeFolder").contains(exeName))

        // Both findings are in the usual lists, labelled, with the usual actions.
        waitAndScrollTo("malware_match_$apkName", 60_000)
        rule.onNodeWithTag("malware_match_$apkName").assertIsDisplayed()
        rule.onNodeWithTag("scheduled_label_quick").assertIsDisplayed()
        waitAndScrollTo("deep_match_$exeName", 60_000)
        rule.onNodeWithTag("deep_match_$exeName").assertIsDisplayed()
        rule.onNodeWithTag("scheduled_label_deep").assertIsDisplayed()

        // The normal confirm dialog and trash, with the normal re-check.
        waitAndScrollTo("malware_trash_$apkName", 30_000)
        rule.onNodeWithTag("malware_trash_$apkName").performClick()
        rule.onNodeWithTag("confirm_button").performClick()
        rule.waitUntil(60_000) { !hasNode("malware_match_$apkName") }
        assertFalse("the file should have left Download", shell("ls /sdcard/Download").contains(apkName))
        assertTrue(Trash(storageRoot()).list().any { it.name == apkName })
    }
}
