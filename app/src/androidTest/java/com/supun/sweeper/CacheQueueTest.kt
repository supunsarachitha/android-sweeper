package com.supun.sweeper

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Ticks this app, starts "Clear caches", comes back from its App info page and expects the summary. */
@RunWith(AndroidJUnit4::class)
class CacheQueueTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val pkg = "com.supun.sweeper"

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    private fun hasNode(tag: String): Boolean = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun weAreOnTop(): Boolean =
        shell("dumpsys activity activities").lines().any { it.contains("topResumedActivity") && it.contains(pkg) }

    @Test fun clearCachesWalksThroughTheTickedAppAndShowsTheSummary() {
        shell("appops set $pkg GET_USAGE_STATS allow")
        rule.waitForIdle()
        rule.onNodeWithTag("apps_card").performClick()
        rule.waitUntil(30_000) { hasNode("app_check_$pkg") }
        rule.onNodeWithTag("apps_list").performScrollToNode(hasTestTag("app_check_$pkg"))
        rule.onNodeWithTag("app_check_$pkg").performClick()
        rule.onNodeWithTag("apps_list").performScrollToNode(hasTestTag("clear_selected_button"))
        rule.onNodeWithTag("clear_selected_button").assertIsDisplayed()
        rule.onNodeWithTag("clear_selected_button").performClick()

        // App info opens on top of the app; wait for it, then go back like the user would.
        val deadline = System.currentTimeMillis() + 15_000
        while (weAreOnTop() && System.currentTimeMillis() < deadline) Thread.sleep(250)
        assertTrue("App info did not open", !weAreOnTop())
        shell("input keyevent KEYCODE_BACK")

        rule.waitUntil(30_000) { hasNode("cache_summary") }
        rule.onNodeWithTag("apps_list").performScrollToNode(hasTestTag("cache_summary"))
        rule.onNodeWithTag("cache_summary").assertIsDisplayed()
        assertTrue(!hasNode("cache_queue_card"))
    }
}
