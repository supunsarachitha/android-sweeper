package com.supun.sweeper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.supun.sweeper.scan.ScanFrequency

internal const val SCHEDULE_NOTE = "Runs while the phone is charging. Updates the malware list first on Wi-Fi."
internal const val SCHEDULE_NOTIFICATIONS_OFF = "Notifications are off: results show only here"

/** The "Scheduled scan" card: Off / Daily / Weekly, what the schedule does, and the last run. */
@Composable
internal fun ScheduleCard(
    frequency: ScanFrequency,
    lastRun: String,
    notificationsOff: Boolean,
    onFrequency: (ScanFrequency) -> Unit,
    onAllowNotifications: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("schedule_card"), colors = sweeperCardColors()) {
        Column(Modifier.padding(16.dp)) {
            Text("Scheduled scan", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in ScanFrequency.entries) {
                    FilterChip(
                        selected = frequency == option,
                        onClick = { onFrequency(option) },
                        label = { Text(option.label) },
                        modifier = Modifier.testTag("schedule_${option.name.lowercase()}"),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(SCHEDULE_NOTE, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("schedule_note"))
            Spacer(Modifier.height(4.dp))
            Text(
                "It never removes anything by itself: you review each finding first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(lastRun, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("schedule_last_run"))
            if (notificationsOff) {
                Spacer(Modifier.height(8.dp))
                Text(
                    SCHEDULE_NOTIFICATIONS_OFF,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("schedule_notifications_off"),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onAllowNotifications, modifier = Modifier.testTag("schedule_allow_notifications")) {
                    Text("Allow notifications")
                }
            }
        }
    }
}
