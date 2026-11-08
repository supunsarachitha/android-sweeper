package com.supun.sweeper.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.supun.sweeper.R
import com.supun.sweeper.files.FoundFile
import com.supun.sweeper.files.MB
import com.supun.sweeper.files.ScanCandidates
import com.supun.sweeper.storage.AllFilesAccess
import com.supun.sweeper.storage.ApkInfo
import com.supun.sweeper.storage.FileCleaner
import com.supun.sweeper.util.formatBytes
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val TAB_LARGE = 0
const val TAB_OLD = 1
const val TAB_APK = 2

private val largeChipsMb = listOf(10, 50, 100, 500)
private val oldChipsDays = listOf(30, 90, 180, 365)

internal fun fileCount(n: Int): String = if (n == 1) "1 file" else "$n files"

/** Folder of [path] relative to the storage root, e.g. "Download/Docs"; "Internal storage" for the root itself. */
internal fun relativeFolder(rootPath: String, path: String): String {
    val parent = File(path).parent ?: return ""
    val rel = parent.removePrefix(rootPath).trimStart('/')
    return if (rel.isEmpty()) "Internal storage" else rel
}

internal fun formatDate(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(resumeCount: Int, initialTab: Int, onBack: () -> Unit) {
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

    var candidates by remember { mutableStateOf<ScanCandidates?>(null) }
    var seen by remember { mutableIntStateOf(0) }
    var scanKey by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var largeMb by rememberSaveable { mutableStateOf(50) }
    var oldDays by rememberSaveable { mutableStateOf(90) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // One walk per visit (and one more after an undo); thresholds are applied to its result.
    LaunchedEffect(granted, scanKey) {
        candidates = null
        selected = emptySet()
        if (!granted) return@LaunchedEffect
        seen = 0
        candidates = try {
            cleaner.scan { seen = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ScanCandidates(emptyList(), 0, System.currentTimeMillis())
        }
    }

    val loaded = candidates
    val visible = remember(loaded, tab, largeMb, oldDays) {
        when {
            loaded == null -> emptyList()
            tab == TAB_LARGE -> loaded.large(largeMb * MB)
            tab == TAB_OLD -> loaded.oldDownloads(oldDays)
            else -> loaded.apks()
        }
    }
    val selectedFiles = visible.filter { it.path in selected }
    val selectedBytes = selectedFiles.sumOf { it.size }

    fun moveSelected() {
        val toMove = selectedFiles
        confirm = false
        busy = true
        scope.launch {
            val result = try {
                cleaner.moveToTrash(toMove)
            } finally {
                busy = false
            }
            val movedPaths = result.moved.map { it.originalPath }.toSet()
            candidates = candidates?.without(movedPaths)
            selected = selected - movedPaths
            val n = result.moved.size
            val problems = result.skipped + result.failed
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
        topBar = {
            TopAppBar(
                title = { Text("Large & old files") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (granted && loaded != null) {
                BottomAppBar(contentPadding = PaddingValues(horizontal = 16.dp)) {
                    Text(
                        "${selectedFiles.size} selected · ${formatBytes(selectedBytes)}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).testTag("selection_summary"),
                    )
                    Button(
                        onClick = { confirm = true },
                        enabled = selectedFiles.isNotEmpty() && !busy,
                        modifier = Modifier.testTag("move_to_trash_button"),
                    ) { Text("Move to trash") }
                }
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
                loaded == null -> ScanProgress(seen)
                else -> {
                    TabRow(selectedTabIndex = tab) {
                        Tab(
                            selected = tab == TAB_LARGE,
                            onClick = { tab = TAB_LARGE; selected = emptySet() },
                            text = { Text("Large files") },
                            modifier = Modifier.testTag("files_tab_large"),
                        )
                        Tab(
                            selected = tab == TAB_OLD,
                            onClick = { tab = TAB_OLD; selected = emptySet() },
                            text = { Text("Old downloads") },
                            modifier = Modifier.testTag("files_tab_old"),
                        )
                        Tab(
                            selected = tab == TAB_APK,
                            onClick = { tab = TAB_APK; selected = emptySet() },
                            text = { Text("APK files") },
                            modifier = Modifier.testTag("files_tab_apk"),
                        )
                    }
                    when (tab) {
                        TAB_LARGE -> ChipRow(
                            options = largeChipsMb,
                            selected = largeMb,
                            label = { "$it MB" },
                            onSelect = { largeMb = it; selected = emptySet() },
                        )
                        TAB_OLD -> ChipRow(
                            options = oldChipsDays,
                            selected = oldDays,
                            label = { "$it days" },
                            onSelect = { oldDays = it; selected = emptySet() },
                        )
                    }
                    val allSelected = visible.isNotEmpty() && selectedFiles.size == visible.size
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${fileCount(visible.size)} · ${formatBytes(visible.sumOf { it.size })}",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { selected = if (allSelected) emptySet() else visible.map { it.path }.toSet() },
                            enabled = visible.isNotEmpty(),
                            modifier = Modifier.testTag("select_all_button"),
                        ) { Text(if (allSelected) "Deselect all" else "Select all") }
                    }
                    if (visible.isEmpty()) {
                        Text(
                            "Nothing found",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("files_list"),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(visible, key = { it.path }) { file ->
                            FileRow(
                                file = file,
                                folder = relativeFolder(rootPath, file.path),
                                checked = file.path in selected,
                                cleaner = cleaner,
                                showApkInfo = tab == TAB_APK,
                                onToggle = {
                                    selected = if (file.path in selected) selected - file.path else selected + file.path
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Move to trash?") },
            text = {
                Text(
                    "Move ${fileCount(selectedFiles.size)} (${formatBytes(selectedBytes)}) to the trash? " +
                        "You can restore them for 7 days.",
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
internal fun AllFilesAccessCard(onAllow: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("all_files_access_card")) {
        Column(Modifier.padding(16.dp)) {
            Text("All files access needed", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Android Sweeper needs All files access to find big, old and leftover files and to move " +
                    "them. Nothing leaves your phone: the app has no internet access. Files you remove go " +
                    "to the trash first and stay there for 7 days, so you can restore them.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAllow, modifier = Modifier.testTag("allow_all_files_button")) {
                Text(if (AllFilesAccess.needsSettingsScreen) "Open All files access settings" else "Allow access")
            }
        }
    }
}

@Composable
private fun ScanProgress(seen: Int) {
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("scan_progress")) {
        Text("Scanning… $seen files", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ChipRow(options: List<Int>, selected: Int, label: (Int) -> String, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

@Composable
private fun FileRow(
    file: FoundFile,
    folder: String,
    checked: Boolean,
    cleaner: FileCleaner,
    showApkInfo: Boolean,
    onToggle: () -> Unit,
) {
    val apk by produceState<ApkInfo?>(null, file.path, showApkInfo) {
        value = if (showApkInfo) withContext(Dispatchers.IO) { cleaner.apkInfo(file.path) } else null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("file_row_${file.name}")
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val info = apk
            if (info != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val version = if (info.versionName.isBlank()) "" else " ${info.versionName}"
                    Text(
                        info.label + version,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (info.installed) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                "Installed",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
            Text(
                folder,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(formatBytes(file.size), style = MaterialTheme.typography.titleSmall)
            Text(
                formatDate(file.lastModified),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
