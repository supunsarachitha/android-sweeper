package com.supun.sweeper

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
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    /** Sets the Usage access app-op through the shell, then restarts the activity so it re-reads it. */
    private fun setUsageAccess(allowed: Boolean) {
        val mode = if (allowed) "allow" else "default"
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set com.supun.sweeper GET_USAGE_STATS $mode")
        // Read to the end so the command has finished before we continue.
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
    }

    @After fun revokeUsageAccess() {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set com.supun.sweeper GET_USAGE_STATS default")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    @Test fun homeScreenShowsTitleAndStorage() {
        rule.onNodeWithText("Android Sweeper").assertIsDisplayed()
        rule.onNodeWithTag("storage_card").assertIsDisplayed()
        rule.onNodeWithText("Internal storage").assertIsDisplayed()
    }

    @Test fun appsScreenAsksForUsageAccessWhenRevoked() {
        setUsageAccess(false)
        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("apps_card"))
        rule.onNodeWithTag("apps_card").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("usage_access_card").assertIsDisplayed()
    }

    @Test fun appsScreenListsOwnAppWhenGranted() {
        setUsageAccess(true)
        // The breakdown appears on Home once the app sizes have been read.
        rule.waitUntil(30_000) {
            rule.onAllNodesWithTag("breakdown_bar").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("breakdown_bar").assertIsDisplayed()

        rule.onNodeWithTag("home_list").performScrollToNode(hasTestTag("apps_card"))

        rule.onNodeWithTag("apps_card").performClick()
        rule.waitUntil(30_000) {
            rule.onAllNodesWithTag("apps_list").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("apps_list").performScrollToNode(hasTestTag("app_row_com.supun.sweeper"))
        rule.onNodeWithTag("app_row_com.supun.sweeper").assertIsDisplayed()
    }
}
