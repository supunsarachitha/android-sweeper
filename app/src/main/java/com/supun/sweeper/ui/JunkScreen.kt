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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.supun.sweeper.R
import com.supun.sweeper.files.JunkResult
import com.supun.sweeper.storage.AllFilesAccess
import com.supun.sweeper.storage.FileCleaner
import com.supun.sweeper.storage.JunkScan
import com.supun.sweeper.storage.JunkStats
import com.supun.sweeper.util.formatBytes
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The four kinds of junk, in the order they are shown. */
private enum class JunkCategory(val title: String, val tag: String, val expandTag: String) {
    EMPTY("Empty folders", "junk_section_empty", "junk_expand_empty"),
    THUMBS("Thumbnail caches", "junk_section_thumbs", "junk_expand_thumbs"),
    TEMP("Temp files", "junk_section_temp", "junk_expand_temp"),
    CACHE("App cache (Android Sweeper)", "junk_section_cache", "junk_expand_cache"),
}

/** One line in a section. [size] is null for empty folders, which hold nothing. */
private class JunkRow(val key: String, val name: String, val folder: String, val size: Long?)

/** Sections with at most this many rows start open; bigger ones start closed so the screen stays short. */
private const val AUTO_EXPAND_MAX = 8

private fun itemCount(n: Int): String = if (n == 1) "1 item" else "$n items"

private fun folderCount(n: Int): String = if (n == 1) "1 folder" else "$n folders"

@Composable
fun JunkScreen(resumeCount: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val cleaner = remember { FileCleaner(context) }
    val rootPath = remember { cleaner.root.canonicalPath }
    // Canonical cache folder -> the label its rows get.
    val cachePaths = remember {
        listOfNotNull(
            context.cacheDir?.let { it.canonicalPath to "Cache" },
            context.externalCacheDir?.let { it.canonicalPath to "External cache" },
        )
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Re-checked on every resume (back from Settings) and after the Android 8-10 permission dialog.
    var permissionTick by remember { mutableIntStateOf(0) }
    val granted = remember(resumeCount, permissionTick) { AllFilesAccess.isGranted(context) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionTick++ }

    var scan by remember { mutableStateOf<JunkScan?>(null) }
    var selected by remember { mutableStateOf(emptySet<JunkCategory>()) }
    var expanded by remember { mutableStateOf(emptySet<JunkCategory>()) }
    var filesSeen by remember { mutableIntStateOf(0) }
    var scanKey by remember { mutableIntStateOf(0) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var scanCancelled by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    // One scan per visit (and one more after cleaning or undoing). Cancel stops it and offers "Scan again".
    LaunchedEffect(granted, scanKey) {
        scan = null
        selected = emptySet()
        scanCancelled = false
        if (!granted) return@LaunchedEffect
        filesSeen = 0
        val job = launch {
            val found = try {
                cleaner.scanJunk { filesSeen = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (found != null) {
                JunkStats.junkBytes = found.totalBytes
                expanded = JunkCategory.entries.filter { rowsOf(found, it, rootPath, cachePaths).size <= AUTO_EXPAND_MAX }.toSet()
            }
            scan = found ?: JunkScan(
                JunkResult(emptyList(), emptyList(), emptyList(), 0, 0L),
                emptyList(),
            )
        }
        scanJob = job
        job.join()
    }

    val loaded = scan
    val selectedCount = loaded?.let { s -> selected.sumOf { rowsOf(s, it, rootPath, cachePaths).size } } ?: 0
    val selectedBytes = loaded?.let { s ->
        selected.sumOf { c -> rowsOf(s, c, rootPath, cachePaths).sumOf { it.size ?: 0L } }
    } ?: 0L

    fun cleanSelected() {
        val current = loaded ?: return
        val chosen = selected
        confirm = false
        busy = true
        scope.launch {
            val result = try {
                cleaner.cleanJunk(
                    files = (if (JunkCategory.THUMBS in chosen) current.junk.thumbnails else emptyList()) +
                        (if (JunkCategory.TEMP in chosen) current.junk.tempFiles else emptyList()),
                    emptyFolders = if (JunkCategory.EMPTY in chosen) current.junk.emptyFolders else emptyList(),
                    cacheEntries = if (JunkCategory.CACHE in chosen) current.appCache else emptyList(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } finally {
                busy = false
            }
            scanKey++ // rescan, so the lists show what is really left
            if (result == null) {
                snackbar.showSnackbar("Cleaning failed")
                return@launch
            }
            val n = result.cleaned
            val trashedIds = result.trashed.moved.map { it.id }
            if (n == 0) {
                snackbar.showSnackbar("Nothing was cleaned")
                return@launch
            }
            val outcome = snackbar.showSnackbar(
                message = "Cleaned $n items",
                actionLabel = if (trashedIds.isEmpty()) null else "Undo",
                duration = SnackbarDuration.Long,
            )
            if (outcome == SnackbarResult.ActionPerformed) {
                cleaner.restore(trashedIds)
                scanKey++
            }
        }
    }

    Scaffold(
        topBar = { SweeperTopBar("Junk cleanup", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (granted && loaded != null) {
                BottomActionBar(
                    summary = "$selectedCount selected · ${formatBytes(selectedBytes)}",
                    buttonText = "Clean selected",
                    enabled = selectedCount > 0 && !busy,
                    onClick = { confirm = true },
                    summaryModifier = Modifier.testTag("selection_summary"),
                    buttonModifier = Modifier.testTag("clean_button"),
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
                loaded == null -> Column(Modifier.fillMaxWidth().padding(16.dp).testTag("scan_progress")) {
                    Text("Looking for junk", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$filesSeen files seen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            scanJob?.cancel()
                            scanCancelled = true
                        },
                        modifier = Modifier.testTag("cancel_scan_button"),
                    ) { Text("Cancel") }
                }
                JunkCategory.entries.all { rowsOf(loaded, it, rootPath, cachePaths).isEmpty() } -> EmptyState(
                    icon = R.drawable.ic_sparkle,
                    title = "No junk found",
                    body = "Your storage is already tidy.",
                    modifier = Modifier.testTag("junk_empty"),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("junk_list"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    for (category in JunkCategory.entries) {
                        val rows = rowsOf(loaded, category, rootPath, cachePaths)
                        val isOpen = category in expanded
                        item(key = "header_${category.name}") {
                            JunkSectionHeader(
                                category = category,
                                count = rows.size,
                                bytes = rows.sumOf { it.size ?: 0L },
                                showSize = category != JunkCategory.EMPTY,
                                checked = category in selected,
                                open = isOpen,
                                onToggle = {
                                    selected = if (category in selected) selected - category else selected + category
                                },
                                onExpand = {
                                    expanded = if (isOpen) expanded - category else expanded + category
                                },
                            )
                        }
                        if (isOpen) {
                            if (rows.isEmpty()) {
                                item(key = "none_${category.name}") {
                                    Text(
                                        "Nothing found",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 52.dp, bottom = 8.dp),
                                    )
                                }
                            }
                            items(rows.size, key = { "row_${category.name}_${rows[it].key}" }) { i ->
                                val row = rows[i]
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("junk_row_${row.name}")
                                        .padding(start = 52.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            row.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            row.folder,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.MiddleEllipsis,
                                        )
                                    }
                                    // Empty folders hold nothing, so they have no size to show.
                                    if (row.size != null) {
                                        Spacer(Modifier.width(12.dp))
                                        Text(nbBytes(row.size), style = MaterialTheme.typography.titleSmall)
                                    }
                                }
                            }
                        }
                        item(key = "divider_${category.name}") { HorizontalDivider(Modifier.padding(top = 4.dp)) }
                    }
                }
            }
        }
    }

    if (confirm && loaded != null) {
        val lines = JunkCategory.entries.filter { it in selected }.map { c ->
            val rows = rowsOf(loaded, c, rootPath, cachePaths)
            val size = if (c == JunkCategory.EMPTY) "" else " (${formatBytes(rows.sumOf { it.size ?: 0L })})"
            "${c.title}: ${rows.size}$size"
        }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Clean selected?") },
            text = {
                Text(
                    lines.joinToString("\n") + "\n\n" +
                        "Thumbnails and temp files go to the trash for 7 days. " +
                        "Empty folders and the app cache are removed right away.",
                )
            },
            confirmButton = {
                TextButton(onClick = { cleanSelected() }, modifier = Modifier.testTag("confirm_button")) {
                    Text("Clean selected")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/** The lines of one section. File rows show their path relative to the storage root. */
private fun rowsOf(scan: JunkScan, category: JunkCategory, rootPath: String, cachePaths: List<Pair<String, String>>): List<JunkRow> =
    when (category) {
        JunkCategory.EMPTY -> scan.junk.emptyFolders.map {
            JunkRow(it, File(it).name, relativeFolder(rootPath, it), null)
        }
        JunkCategory.THUMBS -> scan.junk.thumbnails.map {
            JunkRow(it.path, it.name, relativeFolder(rootPath, it.path), it.size)
        }
        JunkCategory.TEMP -> scan.junk.tempFiles.map {
            JunkRow(it.path, it.name, relativeFolder(rootPath, it.path), it.size)
        }
        JunkCategory.CACHE -> scan.appCache.map { entry ->
            val where = cachePaths.firstOrNull { entry.path.startsWith(it.first + "/") }?.second ?: "Cache"
            JunkRow(entry.path, File(entry.path).name, where, entry.size)
        }
    }

@Composable
private fun JunkSectionHeader(
    category: JunkCategory,
    count: Int,
    bytes: Long,
    showSize: Boolean,
    checked: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    onExpand: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .testTag(category.tag)
                .toggleable(
                    value = checked,
                    enabled = count > 0,
                    role = Role.Checkbox,
                    onValueChange = { onToggle() },
                )
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = count > 0)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(category.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (showSize) "${itemCount(count)} · ${nbBytes(bytes)}" else folderCount(count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onExpand, modifier = Modifier.testTag(category.expandTag)) {
            Icon(
                painterResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                contentDescription = (if (open) "Hide " else "Show ") + category.title,
            )
        }
    }
}
