package com.supun.sweeper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.lifecycleScope
import com.supun.sweeper.scan.ACTION_OPEN_MALWARE
import com.supun.sweeper.scan.ScheduledScan
import com.supun.sweeper.storage.FileCleaner
import com.supun.sweeper.ui.AppsScreen
import com.supun.sweeper.ui.DuplicatesScreen
import com.supun.sweeper.ui.FilesScreen
import com.supun.sweeper.ui.HomeScreen
import com.supun.sweeper.ui.JunkScreen
import com.supun.sweeper.ui.LocalRestrictedHelpExpanded
import com.supun.sweeper.ui.MalwareScreen
import com.supun.sweeper.ui.PreviewScreen
import com.supun.sweeper.ui.PreviewSession
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
            // The scheduled scan's notification opens the Malware screen in every build type.
            else -> if (intent.action == ACTION_OPEN_MALWARE) Screen.Malware else Screen.Home
        }
        val startTab: Int = when (if (BuildConfig.DEBUG) intent.getStringExtra("tab") else null) {
            "old" -> TAB_OLD
            "apk" -> TAB_APK
            else -> TAB_LARGE
        }
        // Debug builds only: "--es preview <path>" opens the preview of that file on the Files screen.
        val demoPreview = (if (BuildConfig.DEBUG) intent.getStringExtra("preview") else null).orEmpty()
        // Debug builds only: preselect "keep oldest" in every group once the duplicate scan finishes.
        val demoKeepOldest = BuildConfig.DEBUG && intent.getStringExtra("demo") == "keepoldest"
        // Debug builds only: "--es select pkg1,pkg2" ticks those apps on the Apps screen (for screenshots).
        val demoSelect = (if (BuildConfig.DEBUG) intent.getStringExtra("select") else null).orEmpty()
        // Debug builds only (guarded by BuildConfig.DEBUG; the release build has no demo code at all):
        // "--es demo malware" installs a demo list signed with a throw-away key made at run time and starts a
        // scan on the Malware screen. "--es demo restricted" shows the restricted-settings help expanded.
        val demoMalware = BuildConfig.DEBUG && intent.getStringExtra("demo") == "malware"
        val demoRestricted = BuildConfig.DEBUG && intent.getStringExtra("demo") == "restricted"
        // "--es demo deep" does the same for the deep scan: a demo ALL list, then a deep scan on the Malware screen.
        // "--es scroll deep" only scrolls the Malware screen to the deep-scan card.
        val demoDeep = BuildConfig.DEBUG && intent.getStringExtra("demo") == "deep"
        val scrollToDeep = BuildConfig.DEBUG && (demoDeep || intent.getStringExtra("scroll") == "deep")
        // "--es demo schedule" shows the schedule card on Daily with a last run; "--es demo schedulerun" prepares a
        // real scheduled run (demo list, no network) that the emulator script then forces.
        val demoName = if (BuildConfig.DEBUG) intent.getStringExtra("demo") else null
        if (BuildConfig.DEBUG && demoName == "schedule") DemoHook.installSchedule(applicationContext)
        if (BuildConfig.DEBUG && demoName == "schedulerun") DemoHook.installScheduleRun(applicationContext, intent.getStringExtra("demohash"))
        if (BuildConfig.DEBUG && demoMalware) DemoHook.install(applicationContext, intent.getStringExtra("demohash"))
        if (BuildConfig.DEBUG && demoDeep) DemoHook.installDeep(applicationContext, intent.getStringExtra("demohash"))
        // Trash items older than 7 days are deleted at app start. The scheduled scan's job is also put back
        // if an update or a restart dropped it.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ScheduledScan.ensureScheduled(applicationContext)
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
                    // The preview of a tapped file lies over its list screen, which stays composed (so its scan
                    // and selection survive); Back closes the preview first.
                    var previewSession by remember { mutableStateOf<PreviewSession?>(null) }
                    val current = screen
                    BackHandler(enabled = current != Screen.Home) {
                        screen = if (current is Screen.Preview) current.origin else Screen.Home
                    }
                    val listScreen = if (current is Screen.Preview) current.origin else current
                    val previewing = current is Screen.Preview
                    Box(Modifier.fillMaxSize()) {
                    // While the preview is up the list is hidden from touch and from screen readers.
                    Box(Modifier.fillMaxSize().then(if (previewing) Modifier.clearAndSetSemantics { } else Modifier)) {
                    when (listScreen) {
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
                            initialSelection = demoSelect,
                        )
                        Screen.Files -> FilesScreen(
                            resumeCount = resumeCount,
                            initialTab = startTab,
                            onBack = { screen = Screen.Home },
                            onPreview = { session ->
                                previewSession = session
                                screen = Screen.Preview(Screen.Files, session.path)
                            },
                            demoPreview = demoPreview,
                        )
                        Screen.Duplicates -> DuplicatesScreen(
                            resumeCount = resumeCount,
                            demoKeepOldest = demoKeepOldest,
                            onBack = { screen = Screen.Home },
                            onPreview = { session ->
                                previewSession = session
                                screen = Screen.Preview(Screen.Duplicates, session.path)
                            },
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
                        is Screen.Preview -> Unit // never: listScreen is the origin
                    }
                    }
                    val session = previewSession
                    if (current is Screen.Preview && session != null) {
                        PreviewScreen(session = session, onBack = { screen = current.origin })
                    }
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
