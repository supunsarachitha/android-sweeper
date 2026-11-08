package com.supun.sweeper.ui

import androidx.compose.runtime.saveable.Saver

/** The screens of the app. Navigation is a single state value; there is no navigation library. */
sealed interface Screen {
    data object Home : Screen
    data object Apps : Screen
}

/** Lets the current screen survive rotation and process death. */
val ScreenSaver: Saver<Screen, String> = Saver(
    save = {
        when (it) {
            Screen.Home -> "home"
            Screen.Apps -> "apps"
        }
    },
    restore = { if (it == "apps") Screen.Apps else Screen.Home },
)
