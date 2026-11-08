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
import com.supun.sweeper.ui.AppsScreen
import com.supun.sweeper.ui.HomeScreen
import com.supun.sweeper.ui.Screen
import com.supun.sweeper.ui.ScreenSaver
import com.supun.sweeper.ui.theme.SweeperTheme

class MainActivity : ComponentActivity() {
    // Bumped on every onResume so screens re-check usage access after the user returns from Settings.
    private var resumeCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Debug builds only: lets CI screenshots start directly on the Apps screen.
        val startScreen: Screen =
            if (BuildConfig.DEBUG && intent.getStringExtra("open") == "apps") Screen.Apps else Screen.Home
        setContent {
            SweeperTheme {
                var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf(startScreen) }
                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
                when (screen) {
                    Screen.Home -> HomeScreen(
                        resumeCount = resumeCount,
                        onOpenApps = { screen = Screen.Apps },
                    )
                    Screen.Apps -> AppsScreen(
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
