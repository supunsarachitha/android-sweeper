package com.supun.sweeper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
            else -> Screen.Home
        }
        val startTab: Int = when (if (BuildConfig.DEBUG) intent.getStringExtra("tab") else null) {
            "old" -> TAB_OLD
            "apk" -> TAB_APK
            else -> TAB_LARGE
        }
        // Debug builds only: preselect "keep oldest" in every group once the duplicate scan finishes.
        val demoKeepOldest = BuildConfig.DEBUG && intent.getStringExtra("demo") == "keepoldest"
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
                    Screen.Trash -> TrashScreen(
                        resumeCount = resumeCount,
                        onBack = { screen = Screen.Home },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }
}
