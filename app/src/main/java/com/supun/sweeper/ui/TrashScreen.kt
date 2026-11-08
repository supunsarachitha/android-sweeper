package com.supun.sweeper.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.supun.sweeper.R
import com.supun.sweeper.files.TrashItem
import com.supun.sweeper.storage.AllFilesAccess
import com.supun.sweeper.storage.FileCleaner
import com.supun.sweeper.util.formatBytes
import kotlinx.coroutines.launch

private sealed interface PendingConfirm {
    data object EmptyAll : PendingConfirm
    data class DeleteOne(val item: TrashItem) : PendingConfirm
}

@Composable
fun TrashScreen(resumeCount: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val cleaner = remember { FileCleaner(context) }
    val rootPath = remember { cleaner.root.canonicalPath }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val granted = remember(resumeCount) { AllFilesAccess.isGranted(context) }

    var trashItems by remember { mutableStateOf<List<TrashItem>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<PendingConfirm?>(null) }

    // Opening the screen first deletes whatever has been in the trash for more than 7 days.
    LaunchedEffect(reload) {
        cleaner.purgeExpired()
        trashItems = cleaner.trashItems()
    }
    val now = remember(trashItems) { System.currentTimeMillis() }
    val list = trashItems

    Scaffold(
        topBar = {
            SweeperTopBar(
                "Trash",
                onBack = onBack,
                actions = {
                    TextButton(
                        onClick = { pending = PendingConfirm.EmptyAll },
                        enabled = !list.isNullOrEmpty(),
                        modifier = Modifier.testTag("empty_trash_button"),
                    ) { Text("Empty trash") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!granted) {
                InfoCard(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "All files access is off. Turn it on from Large & old files to manage the trash.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (list != null && list.isEmpty()) {
                EmptyState(
                    icon = R.drawable.ic_trash,
                    title = "The trash is empty",
                    body = "Files you remove are kept here for 7 days.",
                    modifier = Modifier.testTag("trash_empty"),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("trash_list"),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(list.orEmpty(), key = { it.id }) { item ->
                    TrashRow(
                        item = item,
                        folder = relativeFolder(rootPath, item.originalPath),
                        now = now,
                        onRestore = {
                            scope.launch {
                                val ok = cleaner.restore(listOf(item.id)) == 1
                                reload++
                                snackbar.showSnackbar(if (ok) "Restored ${item.name}" else "Could not restore ${item.name}")
                            }
                        },
                        onDelete = { pending = PendingConfirm.DeleteOne(item) },
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    val ask = pending
    if (ask != null) {
        val (title, text) = when (ask) {
            PendingConfirm.EmptyAll ->
                "Empty trash?" to "Permanently delete ${list.orEmpty().size} items (${formatBytes(list.orEmpty().sumOf { it.size })})? This cannot be undone."
            is PendingConfirm.DeleteOne ->
                "Delete now?" to "Permanently delete ${ask.item.name} (${formatBytes(ask.item.size)})? This cannot be undone."
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        scope.launch {
                            when (ask) {
                                PendingConfirm.EmptyAll -> cleaner.emptyTrash()
                                is PendingConfirm.DeleteOne -> cleaner.deleteNow(ask.item.id)
                            }
                            reload++
                        }
                    },
                    modifier = Modifier.testTag("confirm_button"),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TrashRow(item: TrashItem, folder: String, now: Long, onRestore: () -> Unit, onDelete: () -> Unit) {
    val days = item.daysLeft(now)
    val expiry = when (days) {
        0 -> "deletes soon"
        1 -> "deletes in 1 day"
        else -> "deletes in $days days"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("trash_row_${item.name}")
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    folder,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    expiry,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(nbBytes(item.size), style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(2.dp))
        Row {
            TextButton(onClick = onRestore, modifier = Modifier.testTag("restore_button_${item.name}")) { Text("Restore") }
            TextButton(onClick = onDelete, modifier = Modifier.testTag("delete_button_${item.name}")) { Text("Delete now") }
        }
    }
}
