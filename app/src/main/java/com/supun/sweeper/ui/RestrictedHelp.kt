package com.supun.sweeper.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Debug builds only: the help starts expanded (used for screenshots). Always false in a normal run. */
val LocalRestrictedHelpExpanded = staticCompositionLocalOf { false }

/**
 * Android 13+ blocks Usage access and All files access for sideloaded apps until "Allow restricted settings"
 * is chosen in App info. This collapsible section explains how. It does not exist on older Android versions.
 */
@Composable
fun RestrictedSettingsHelp(modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        RestrictedSettingsHelpBody(modifier)
    }
}

@Composable
private fun RestrictedSettingsHelpBody(modifier: Modifier) {
    val context = LocalContext.current
    val startExpanded = LocalRestrictedHelpExpanded.current
    var expanded by rememberSaveable { mutableStateOf(startExpanded) }
    Column(modifier.fillMaxWidth().testTag("restricted_help")) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("restricted_help_toggle")) {
            Text(if (expanded) "Switch greyed out? ▴" else "Switch greyed out? ▾")
        }
        if (expanded) {
            Column(Modifier.padding(start = 12.dp, end = 4.dp)) {
                Text("1. Tap the greyed-out switch once and close the message.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text("2. Open App info for Android Sweeper.", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { openAppDetails(context, context.packageName) },
                    modifier = Modifier.testTag("restricted_help_app_info"),
                ) { Text("Open App info") }
                Spacer(Modifier.height(4.dp))
                Text(
                    "3. Tap ⋮ (top right) → \"Allow restricted settings\" and confirm.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text("4. Go back and turn the switch on.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
