package com.supun.sweeper.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.supun.sweeper.R
import com.supun.sweeper.files.DuplicateGroup
import com.supun.sweeper.files.DuplicateSelection
import com.supun.sweeper.files.STAGE_FULL
import com.supun.sweeper.files.STAGE_PREFIX
import com.supun.sweeper.files.withoutFiles
import com.supun.sweeper.storage.AllFilesAccess
import com.supun.sweeper.storage.DuplicateStats
import com.supun.sweeper.storage.FileCleaner
import com.supun.sweeper.util.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private fun stageName(stage: Int): String = when (stage) {
    STAGE_PREFIX -> "Checking file starts"
    STAGE_FULL -> "Comparing contents"
    else -> "Comparing sizes"
}

private fun copies(n: Int): String = "$n copies"

@Composable
fun DuplicatesScreen(resumeCount: Int, demoKeepOldest: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val cleaner = remember { FileCleaner(context) }
    val rootPath = remember { cleaner.root.canonicalPath }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Re-checked on every resume (back from Settings) and after the Android 8-10 permission dialog.
    var permissionTick by remember { mutableIntStateOf(0) }
    val granted = remember(resumeCount, permissionTick) { AllFilesAccess.isGranted(context) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionTick++ }

    var groups by remember { mutableStateOf<List<DuplicateGroup>?>(null) }
    var selection by remember { mutableStateOf(DuplicateSelection()) }
    var stage by remember { mutableIntStateOf(0) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var scanKey by remember { mutableIntStateOf(0) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var scanCancelled by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // One scan per visit (and one more after an undo). Cancel stops it and offers "Scan again".
    LaunchedEffect(granted, scanKey) {
        groups = null
        selection = DuplicateSelection()
        scanCancelled = false
        if (!granted) return@LaunchedEffect
        stage = 0
        done = 0
        total = 0
        val job = launch {
            val found = try {
                cleaner.findDuplicates { s, d, t ->
                    stage = s
                    done = d
                    total = t
                }.groups
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            DuplicateStats.wastedBytes = found.sumOf { it.wasted }
            groups = found
            if (demoKeepOldest) selection = DuplicateSelection().selectAllKeepOne(found)
        }
        scanJob = job
        job.join()
    }

    val loaded = groups
    val selectedBytes = loaded?.let { selection.bytes(it) } ?: 0L

    fun moveSelected() {
        val current = groups ?: return
        val chosen = selection
        confirm = false
        busy = true
        scope.launch {
            val move = try {
                cleaner.trashDuplicates(current, chosen)
            } catch (e: IllegalStateException) {
                null // the guard refused: a group would have lost every copy
            } finally {
                busy = false
            }
            if (move == null) {
                snackbar.showSnackbar("Nothing was moved: every copy of a file was selected")
                return@launch
            }
            val result = move.trash
            val movedPaths = result.moved.map { it.originalPath }.toSet()
            val remaining = withoutFiles(groups ?: current, movedPaths)
            groups = remaining
            selection = selection.retainIn(remaining)
            DuplicateStats.wastedBytes = remaining.sumOf { it.wasted }
            val n = result.moved.size
            val problems = result.skipped + result.failed + move.heldBack
            val note = if (problems > 0) " ($problems skipped)" else ""
            if (n == 0) {
                snackbar.showSnackbar("Nothing was moved$note")
                return@launch
            }
            val outcome = snackbar.showSnackbar(
                message = "Moved ${fileCount(n)} to the trash$note",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
            )
            if (outcome == SnackbarResult.ActionPerformed) {
                cleaner.restore(result.moved.map { it.id })
                scanKey++
            }
        }
    }

    Scaffold(
        topBar = { SweeperTopBar("Duplicates", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (granted && loaded != null) {
                BottomActionBar(
                    summary = "${selection.size} selected · ${nbBytes(selectedBytes)}",
                    buttonText = "Move to trash",
                    enabled = selection.size > 0 && !busy,
                    onClick = { confirm = true },
                    summaryModifier = Modifier.testTag("selection_summary"),
                    buttonModifier = Modifier.testTag("move_to_trash_button"),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                !granted -> AllFilesAccessCard(
                    onAllow = {
                        if (AllFilesAccess.needsSettingsScreen) {
                            AllFilesAccess.openSettings(context)
                        } else {
                            permissionLauncher.launch(AllFilesAccess.legacyPermissions)
                        }
                    },
                )
                scanCancelled -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Scan cancelled", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { scanKey++ }, modifier = Modifier.testTag("scan_again_button")) {
                        Text("Scan again")
                    }
                }
                loaded == null -> DuplicateProgress(
                    stage = stage,
                    done = done,
                    total = total,
                    onCancel = {
                        scanJob?.cancel()
                        scanCancelled = true
                    },
                )
                else -> {
                    val wasted = loaded.sumOf { it.wasted }
                    val groupLabel = if (loaded.size == 1) "1 group" else "${loaded.size} groups"
                    if (loaded.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
                            Text(
                                "$groupLabel · ${nbBytes(wasted)} can be freed",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.testTag("duplicates_header"),
                            )
                            TextButton(
                                onClick = { selection = selection.selectAllKeepOne(loaded) },
                                modifier = Modifier.testTag("keep_oldest_all_button"),
                            ) { Text("Select duplicates (keep oldest)") }
                        }
                    } else {
                        EmptyState(
                            icon = R.drawable.ic_copy,
                            title = "No duplicates found",
                            body = "Every file on your storage is unique.",
                            modifier = Modifier.testTag("duplicates_empty"),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("duplicates_list"),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        itemsIndexed(loaded, key = { _, g -> g.oldest.path }) { index, group ->
                            GroupCard(
                                index = index,
                                group = group,
                                selection = selection,
                                rootPath = rootPath,
                                onToggle = { path -> selection = selection.toggle(group, path) },
                                onKeepOldest = { selection = selection.keepOldest(group) },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }

    if (confirm) {
        val count = selection.size
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Move to trash?") },
            text = {
                Text(
                    "Move ${fileCount(count)} (${formatBytes(selectedBytes)}) to the trash? " +
                        "One copy of every file stays where it is. You can restore them for 7 days.",
                )
            },
            confirmButton = {
                TextButton(onClick = { moveSelected() }, modifier = Modifier.testTag("confirm_button")) {
                    Text("Move to trash")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DuplicateProgress(stage: Int, done: Int, total: Int, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("scan_progress")) {
        Text(stageName(stage), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            if (stage == 0 || total == 0) "$done files seen" else "$done of $total files",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (stage == 0 || total == 0) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onCancel, modifier = Modifier.testTag("cancel_scan_button")) { Text("Cancel") }
    }
}

@Composable
private fun GroupCard(
    index: Int,
    group: DuplicateGroup,
    selection: DuplicateSelection,
    rootPath: String,
    onToggle: (String) -> Unit,
    onKeepOldest: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().testTag("dup_group_$index"), colors = sweeperCardColors()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        group.oldest.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${copies(group.files.size)} · ${formatBytes(group.size)} each",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = onKeepOldest, modifier = Modifier.testTag("keep_oldest_$index")) {
                    Text("Keep oldest")
                }
            }
            group.files.forEachIndexed { i, file ->
                val checked = file.path in selection
                val enabled = !selection.isLastKept(group, file.path)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dup_row_${file.name}_$i")
                        .toggleable(
                            value = checked,
                            enabled = enabled,
                            role = Role.Checkbox,
                            onValueChange = { onToggle(file.path) },
                        )
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            file.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            relativeFolder(rootPath, file.path),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                        Text(
                            formatDate(file.lastModified) + if (i == 0) " · oldest" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
