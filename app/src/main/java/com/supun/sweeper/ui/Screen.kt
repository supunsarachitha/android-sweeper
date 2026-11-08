package com.supun.sweeper.ui

import androidx.compose.runtime.saveable.Saver

/** The screens of the app. Navigation is a single state value; there is no navigation library. */
sealed interface Screen {
    data object Home : Screen
    data object Apps : Screen
    data object Files : Screen
    data object Trash : Screen
    data object Duplicates : Screen
    data object Junk : Screen
    data object Malware : Screen
}

/** Lets the current screen survive rotation and process death. */
val ScreenSaver: Saver<Screen, String> = Saver(
    save = {
        when (it) {
            Screen.Home -> "home"
            Screen.Apps -> "apps"
            Screen.Files -> "files"
            Screen.Trash -> "trash"
            Screen.Duplicates -> "duplicates"
            Screen.Junk -> "junk"
            Screen.Malware -> "malware"
        }
    },
    restore = {
        when (it) {
            "apps" -> Screen.Apps
            "files" -> Screen.Files
            "trash" -> Screen.Trash
            "duplicates" -> Screen.Duplicates
            "junk" -> Screen.Junk
            "malware" -> Screen.Malware
            else -> Screen.Home
        }
    },
)
