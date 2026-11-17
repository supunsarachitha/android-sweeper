package com.supun.sweeper.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** Asked before an APK file is handed to Android's package installer (which then asks again). */
@Composable
internal fun InstallConfirmDialog(onCancel: () -> Unit, onInstall: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Install this app?") },
        text = { Text("Only install apps you trust.") },
        confirmButton = {
            TextButton(onClick = onInstall, modifier = Modifier.testTag("install_confirm_button")) { Text("Install") }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("install_cancel_button")) { Text("Cancel") }
        },
        modifier = Modifier.testTag("install_dialog"),
    )
}
