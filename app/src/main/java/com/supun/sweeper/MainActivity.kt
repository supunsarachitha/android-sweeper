package com.supun.sweeper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.supun.sweeper.storage.FileCleaner
import com.supun.sweeper.ui.AppsScreen
import com.supun.sweeper.ui.DuplicatesScreen
import com.supun.sweeper.ui.FilesScreen
import com.supun.sweeper.ui.HomeScreen
import com.supun.sweeper.ui.JunkScreen
import com.supun.sweeper.ui.LocalRestrictedHelpExpanded
import com.supun.sweeper.ui.MalwareScreen
import com.supun.sweeper.ui.Screen
import com.supun.sweeper.ui.ScreenSaver
import com.supun.sweeper.ui.TAB_APK
import com.supun.sweeper.ui.TAB_LARGE
import com.supun.sweeper.ui.TAB_OLD
import com.supun.sweeper.ui.TrashScreen
import com.supun.sweeper.ui.theme.SweeperTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Bumped on every onResume so screens re-check usage access after the user returns from Settings.
    private var resumeCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Debug builds only: lets CI screenshots start directly on the Apps screen.
        val debugOpen = if (BuildConfig.DEBUG) intent.getStringExtra("open") else null
        val startScreen: Screen = when (debugOpen) {
            "apps" -> Screen.Apps
            "files" -> Screen.Files
            "trash" -> Screen.Trash
            "duplicates" -> Screen.Duplicates
            "junk" -> Screen.Junk
            "malware" -> Screen.Malware
            else -> Screen.Home
        }
        val startTab: Int = when (if (BuildConfig.DEBUG) intent.getStringExtra("tab") else null) {
            "old" -> TAB_OLD
            "apk" -> TAB_APK
            else -> TAB_LARGE
        }
        // Debug builds only: preselect "keep oldest" in every group once the duplicate scan finishes.
        val demoKeepOldest = BuildConfig.DEBUG && intent.getStringExtra("demo") == "keepoldest"
        // Debug builds only (guarded by BuildConfig.DEBUG; the release build has no demo code at all):
        // "--es demo malware" installs a demo list signed with a throw-away key made at run time and starts a
        // scan on the Malware screen. "--es demo restricted" shows the restricted-settings help expanded.
        val demoMalware = BuildConfig.DEBUG && intent.getStringExtra("demo") == "malware"
        val demoRestricted = BuildConfig.DEBUG && intent.getStringExtra("demo") == "restricted"
        // "--es demo deep" does the same for the deep scan: a demo ALL list, then a deep scan on the Malware screen.
        // "--es scroll deep" only scrolls the Malware screen to the deep-scan card.
        val demoDeep = BuildConfig.DEBUG && intent.getStringExtra("demo") == "deep"
        val scrollToDeep = BuildConfig.DEBUG && (demoDeep || intent.getStringExtra("scroll") == "deep")
        if (BuildConfig.DEBUG && demoMalware) DemoHook.install(applicationContext, intent.getStringExtra("demohash"))
        if (BuildConfig.DEBUG && demoDeep) DemoHook.installDeep(applicationContext, intent.getStringExtra("demohash"))
        // Trash items older than 7 days are deleted at app start.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                FileCleaner(applicationContext).purgeExpired()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing to purge, or no file access yet.
            }
        }
        setContent {
            SweeperTheme {
                CompositionLocalProvider(LocalRestrictedHelpExpanded provides demoRestricted) {
                    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf(startScreen) }
                    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
                    when (screen) {
                        Screen.Home -> HomeScreen(
                            resumeCount = resumeCount,
                            onOpenApps = { screen = Screen.Apps },
                            onOpenFiles = { screen = Screen.Files },
                            onOpenTrash = { screen = Screen.Trash },
                            onOpenDuplicates = { screen = Screen.Duplicates },
                            onOpenJunk = { screen = Screen.Junk },
                            onOpenMalware = { screen = Screen.Malware },
                        )
                        Screen.Apps -> AppsScreen(
                            resumeCount = resumeCount,
                            onBack = { screen = Screen.Home },
                        )
                        Screen.Files -> FilesScreen(
                            resumeCount = resumeCount,
                            initialTab = startTab,
                            onBack = { screen = Screen.Home },
                        )
                        Screen.Duplicates -> DuplicatesScreen(
                            resumeCount = resumeCount,
                            demoKeepOldest = demoKeepOldest,
                            onBack = { screen = Screen.Home },
                        )
                        Screen.Junk -> JunkScreen(
                            resumeCount = resumeCount,
                            onBack = { screen = Screen.Home },
                        )
                        Screen.Malware -> MalwareScreen(
                            resumeCount = resumeCount,
                            autoScan = demoMalware,
                            autoDeepScan = demoDeep,
                            scrollToDeep = scrollToDeep,
                            onBack = { screen = Screen.Home },
                        )
                        Screen.Trash -> TrashScreen(
                            resumeCount = resumeCount,
                            onBack = { screen = Screen.Home },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }
}
