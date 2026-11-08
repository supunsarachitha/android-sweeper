package com.supun.sweeper.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.supun.sweeper.R
import com.supun.sweeper.malware.DeepMatch
import com.supun.sweeper.malware.DeepScanResult
import com.supun.sweeper.malware.SignatureStore
import com.supun.sweeper.malware.formatCount
import com.supun.sweeper.malware.formatSignatureDate
import com.supun.sweeper.util.formatBytes

internal const val DEEP_NON_ANDROID_NOTE =
    "Can't run on Android, but it is dangerous on a computer. Don't copy or open it there."

private fun filesText(n: Int): String = if (n == 1) "1 file" else "${formatCount(n)} files"

/** The "Deep scan (all files)" card: what it does, the list status, the buttons and the progress. */
@Composable
internal fun DeepScanCard(
    header: SignatureStore.Header?,
    granted: Boolean,
    running: Boolean,
    downloading: Boolean,
    files: Int,
    bytes: Long,
    busy: Boolean,
    message: String?,
    includeLarge: Boolean,
    onIncludeLargeChange: (Boolean) -> Unit,
    onAllow: () -> Unit,
    onScan: () -> Unit,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
) {
    val samples = if (header != null) "${formatCount(header.count.toInt())} known malware samples" else "a large list of known malware samples"
    Card(modifier = Modifier.fillMaxWidth().testTag("deep_scan_card"), colors = sweeperCardColors()) {
        Column(Modifier.padding(16.dp)) {
            Text("Deep scan (all files)", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Checks every file on your storage, not only apps, against $samples of all kinds. " +
                    "Files over 100 MB are skipped unless you include them below. " +
                    "This can take a long time on a full phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            if (header != null) {
                Text(
                    "List from ${formatSignatureDate(header.createdAt)} · ${formatCount(header.count.toInt())} known samples",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("deep_list_info"),
                )
            } else {
                Text(
                    "Not downloaded yet. Downloads a larger list the first time.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("deep_list_missing"),
                )
            }
            Spacer(Modifier.height(12.dp))
            if (!granted) {
                AllFilesAccessCard(onAllow = onAllow)
            } else if (running) {
                Column(Modifier.testTag("deep_scan_progress")) {
                    Text(
                        if (downloading) "Downloading the deep-scan list…" else "${filesText(files)} · ${formatBytes(bytes)} checked",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("deep_scan_cancel")) { Text("Cancel") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Include files over 100 MB (slower)",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = includeLarge,
                        onCheckedChange = onIncludeLargeChange,
                        enabled = !busy,
                        modifier = Modifier.testTag("deep_include_large"),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onScan, enabled = !busy, modifier = Modifier.testTag("deep_scan_button")) {
                    Text("Deep scan")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onUpdate, enabled = !busy, modifier = Modifier.testTag("update_deep_list_button")) {
                    Text("Update deep-scan list")
                }
            }
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("deep_message"))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "The list is one signed file from GitHub, downloaded only when you tap a button here. Nothing else is sent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun DeepCleanCard(result: DeepScanResult) {
    Card(modifier = Modifier.fillMaxWidth().testTag("deep_scan_clean"), colors = sweeperCardColors()) {
        EmptyState(
            icon = R.drawable.ic_shield,
            title = "No known malware found",
            body = "${filesText(result.filesChecked)} checked (${nbBytes(result.bytesHashed)})",
        )
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            if (result.tooLarge > 0) {
                Text(
                    "${filesText(result.tooLarge)} over 100 MB skipped (switch on \"Include files over 100 MB\" to check them).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (result.skipped > 0) {
                Text(
                    "${filesText(result.skipped)} could not be read and were skipped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Android samples get the red card; samples for other systems get an amber one with an explanation. */
@Composable
internal fun DeepMatchCard(match: DeepMatch, busy: Boolean, onTrash: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (match.isAndroid) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    } else {
        CardDefaults.cardColors(
            containerColor = if (dark) Color(0xFF5C3B00) else Color(0xFFFFE0B2),
            contentColor = if (dark) Color(0xFFFFE0B2) else Color(0xFF3E2400),
        )
    }
    Card(modifier = Modifier.fillMaxWidth().testTag("deep_match_${match.fileName}"), colors = colors) {
        Column(Modifier.padding(16.dp)) {
            Text(match.family, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(match.fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                match.path.substringBeforeLast('/', ""),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            if (!match.isAndroid) {
                Spacer(Modifier.height(4.dp))
                Text(DEEP_NON_ANDROID_NOTE, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onTrash, enabled = !busy, modifier = Modifier.testTag("deep_trash_${match.fileName}")) {
                Text("Move to trash")
            }
        }
    }
}
