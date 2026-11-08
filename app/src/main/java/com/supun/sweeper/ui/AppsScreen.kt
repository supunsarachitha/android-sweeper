package com.supun.sweeper.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.storage.StorageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.supun.sweeper.R
import com.supun.sweeper.storage.AllFilesAccess
import com.supun.sweeper.storage.AppSort
import com.supun.sweeper.storage.AppStorage
import com.supun.sweeper.storage.CacheQueue
import com.supun.sweeper.storage.StorageRepository
import com.supun.sweeper.storage.UsageAccess
import com.supun.sweeper.storage.cacheOf
import com.supun.sweeper.storage.filterApps
import com.supun.sweeper.storage.sortApps
import com.supun.sweeper.storage.totalCache
import com.supun.sweeper.util.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val sortLabels = listOf(
    AppSort.TOTAL to "Total",
    AppSort.CACHE to "Cache",
    AppSort.DATA to "Data",
    AppSort.APP to "App",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(resumeCount: Int, onBack: () -> Unit, initialSelection: String = "") {
    val context = LocalContext.current
    val repo = remember { StorageRepository(context) }
    // Re-checked on every resume, so returning from Usage access settings updates the screen.
    val granted = remember(resumeCount) { UsageAccess.isGranted(context) }
    val filesGranted = remember(resumeCount) { AllFilesAccess.isGranted(context) }
    val clearAllAvailable = remember { clearAllCachesAvailable(context) }

    var apps by remember { mutableStateOf<List<AppStorage>?>(null) }
    var progress by remember { mutableStateOf(0 to 0) }
    var sort by remember { mutableStateOf(AppSort.TOTAL) }
    var showSystem by remember { mutableStateOf(false) }
    var scanKey by remember { mutableIntStateOf(0) }

    // Ticked apps and the one-by-one walk; saved so they survive Android stopping the app while in Settings.
    var selectedText by rememberSaveable { mutableStateOf(initialSelection) }
    val selected = remember(selectedText) { selectedText.split(",").filter { it.isNotEmpty() }.toSet() }
    var queueText by rememberSaveable { mutableStateOf("") }
    val queue = remember(queueText) { CacheQueue.decode(queueText) }
    // The before/after summary of the last walk or "clear all": label, packages ("*" = all apps), bytes before.
    var summaryText by rememberSaveable { mutableStateOf("") }
    var summaryAfter by remember { mutableStateOf<Long?>(null) }

    fun setQueue(q: CacheQueue?) { queueText = q?.encode() ?: "" }
    fun finishWith(label: String, packages: Collection<String>, before: Long) {
        summaryText = listOf(label, before.toString(), packages.joinToString(",")).joinToString("|")
        summaryAfter = null
        apps = null // so the "after" number is only taken from the new scan
        scanKey++ // rescan to show the cache that is left
    }
    fun openNext(q: CacheQueue) {
        val pkg = q.current ?: return
        setQueue(q.opened())
        openAppDetails(context, pkg)
    }

    val clearAllLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val all = apps.orEmpty()
            finishWith("All app caches", listOf("*"), totalCache(all))
        }
    }

    // One scan per visit, one more when access has just been granted, and one after cleaning.
    LaunchedEffect(granted, scanKey) {
        if (!granted) return@LaunchedEffect
        apps = null
        progress = 0 to 0
        apps = try {
            repo.apps { done, total -> progress = done to total }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Back from an App info page that the walk opened: move on to the next app.
    LaunchedEffect(resumeCount) {
        val q = CacheQueue.decode(queueText) ?: return@LaunchedEffect
        if (!q.waitingForReturn) return@LaunchedEffect
        val next = q.returned()
        if (next.finished) {
            setQueue(null)
            finishWith(if (next.packages.size == 1) "Cache of 1 app" else "Cache of ${next.packages.size} apps", next.packages, next.cacheBefore)
        } else {
            setQueue(next)
        }
    }

    // The next page opens by itself after a short pause, so the user has a moment to tap Stop.
    LaunchedEffect(queueText) {
        val q = CacheQueue.decode(queueText) ?: return@LaunchedEffect
        if (q.waitingForReturn || q.finished || q.position == 0) return@LaunchedEffect
        delay(AUTO_NEXT_DELAY_MS)
        openNext(q)
    }

    // Fill in the "after" number once the rescan is done.
    LaunchedEffect(apps, summaryText) {
        val loaded = apps ?: return@LaunchedEffect
        val parts = summaryText.split("|", limit = 3)
        if (parts.size != 3) return@LaunchedEffect
        val packages = parts[2].split(",").filter { it.isNotEmpty() }
        summaryAfter = if (packages == listOf("*")) totalCache(loaded) else cacheOf(packages, loaded)
    }

    Scaffold(
        topBar = { SweeperTopBar("Apps", onBack = onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val loaded = apps
            when {
                !granted -> PermissionCard(
                    onOpenSettings = { startSafely(context, UsageAccess.settingsIntent(context)) },
                )
                queue != null -> QueueCard(
                    queue = queue,
                    labelOf = { pkg -> loaded?.firstOrNull { it.packageName == pkg }?.label ?: pkg },
                    onOpen = { openNext(queue) },
                    onSkip = {
                        val next = queue.skip()
                        if (next.finished) {
                            setQueue(null)
                            finishWith("Cache of ${next.packages.size} apps", next.packages, next.cacheBefore)
                        } else {
                            setQueue(next)
                        }
                    },
                    onStop = {
                        setQueue(null)
                        finishWith("Cache of the selected apps", queue.packages, queue.cacheBefore)
                    },
                )
                loaded == null -> ScanProgress(progress.first, progress.second)
                else -> {
                    val visible = remember(loaded, sort, showSystem) {
                        sortApps(filterApps(loaded, showSystem), sort)
                    }
                    val summaryParts = summaryText.split("|", limit = 3)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("apps_list"),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        if (summaryParts.size == 3) {
                            item(key = "summary") {
                                SummaryCard(
                                    label = summaryParts[0],
                                    before = summaryParts[1].toLongOrNull() ?: 0,
                                    after = summaryAfter,
                                    onDismiss = { summaryText = ""; summaryAfter = null },
                                )
                            }
                        }
                        item(key = "header") {
                            AppsHeader(count = visible.size, cacheTotal = totalCache(visible))
                            SortChips(sort) { sort = it }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(value = showSystem, role = Role.Switch, onValueChange = { showSystem = it })
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Show system apps", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                Switch(checked = showSystem, onCheckedChange = null)
                            }
                        }
                        if (clearAllAvailable) {
                            item(key = "clear_all") {
                                ClearAllCard(
                                    cacheTotal = totalCache(loaded),
                                    filesGranted = filesGranted,
                                    onAllow = { AllFilesAccess.openSettings(context) },
                                    onClearAll = {
                                        try {
                                            clearAllLauncher.launch(Intent(StorageManager.ACTION_CLEAR_APP_CACHE))
                                        } catch (e: ActivityNotFoundException) {
                                            // The phone advertised the screen but could not open it; nothing to do.
                                        } catch (e: SecurityException) {
                                            // All files access was taken away in the meantime.
                                        }
                                    },
                                )
                            }
                        }
                        item(key = "selection") {
                            SelectionBar(
                                count = selected.size,
                                cache = cacheOf(selected, loaded),
                                onClearCaches = {
                                    val q = CacheQueue.start(selected, loaded) ?: return@SelectionBar
                                    selectedText = ""
                                    summaryText = ""
                                    openNext(q)
                                },
                                onSelectNone = { selectedText = "" },
                            )
                        }
                        if (visible.isEmpty()) {
                            item(key = "empty") { EmptyState(icon = R.drawable.ic_apps, title = "No apps to show") }
                        }
                        items(visible, key = { it.packageName }) { app ->
                            AppRow(
                                app = app,
                                checked = app.packageName in selected,
                                onCheckedChange = { on ->
                                    val next = if (on) selected + app.packageName else selected - app.packageName
                                    selectedText = next.joinToString(",")
                                },
                                onClick = { openAppDetails(context, app.packageName) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val AUTO_NEXT_DELAY_MS = 1_500L

/** Android 11+ has a system screen that clears every app's cache; some phones leave it out. */
private fun clearAllCachesAvailable(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        context.packageManager.resolveActivity(Intent(StorageManager.ACTION_CLEAR_APP_CACHE), 0) != null

@Composable
private fun ClearAllCard(cacheTotal: Long, filesGranted: Boolean, onAllow: () -> Unit, onClearAll: () -> Unit) {
    var howOpen by rememberSaveable { mutableStateOf(false) }
    InfoCard(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("clear_all_card"),
        icon = null,
    ) {
        Text("Clear all app caches", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(2.dp))
        Text(
            "Clears every app's cache in one go. Your data, logins and files are not touched.",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { howOpen = !howOpen }, modifier = Modifier.testTag("clear_all_how")) {
            Text(if (howOpen) "Hide details" else "How it works")
        }
        if (howOpen) {
            Text(
                "Android clears the cache of every app at once after you confirm. Apps rebuild their cache " +
                    "as you use them.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (filesGranted) {
            Button(onClick = onClearAll, modifier = Modifier.testTag("clear_all_button")) {
                Text("Clear all caches (${nbBytes(cacheTotal)})")
            }
        } else {
            Text(
                "Android only offers this to apps with All files access.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onAllow, modifier = Modifier.testTag("clear_all_allow")) {
                Text("Allow All files access")
            }
        }
    }
}

@Composable
private fun SelectionBar(count: Int, cache: Long, onClearCaches: () -> Unit, onSelectNone: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("selection_bar")) {
        if (count == 0) {
            Text(
                "Tick apps to clear their caches one after another.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "${if (count == 1) "1 app" else "$count apps"} · cache ${nbBytes(cache)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onSelectNone) { Text("None") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onClearCaches, modifier = Modifier.testTag("clear_selected_button")) {
                    Text("Clear caches")
                }
            }
        }
    }
}

@Composable
private fun QueueCard(
    queue: CacheQueue,
    labelOf: (String) -> String,
    onOpen: () -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit,
) {
    val pkg = queue.current ?: return
    val total = queue.packages.size
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("cache_queue_card"), colors = sweeperCardColors()) {
        Column(Modifier.padding(16.dp)) {
            Text("Clearing caches · ${queue.position + 1} of $total", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { queue.position.toFloat() / total },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (queue.waitingForReturn) "Opened App info for ${labelOf(pkg)}." else "Next: ${labelOf(pkg)}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("cache_queue_next"),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "On its App info page, open Storage & cache, tap Clear cache, then go back. " +
                    "The next app opens by itself.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen, modifier = Modifier.testTag("cache_queue_open")) { Text("Open now") }
                OutlinedButton(onClick = onSkip, enabled = !queue.waitingForReturn) { Text("Skip") }
                TextButton(onClick = onStop, modifier = Modifier.testTag("cache_queue_stop")) { Text("Stop") }
            }
        }
    }
}

@Composable
private fun SummaryCard(label: String, before: Long, after: Long?, onDismiss: () -> Unit) {
    InfoCard(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("cache_summary"),
        onDismiss = onDismiss,
    ) {
        Text(
            if (after == null) {
                "$label: ${nbBytes(before)} before · checking…"
            } else {
                "$label: ${nbBytes(before)} → ${nbBytes(after)} (${nbBytes((before - after).coerceAtLeast(0))} freed)"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun PermissionCard(onOpenSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("usage_access_card"), colors = sweeperCardColors()) {
        Column(Modifier.padding(16.dp)) {
            Text("Usage access needed", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Android Sweeper needs Usage access to read how much space each app uses. " +
                    "It only reads sizes; nothing about your apps or files is sent anywhere.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenSettings) { Text("Open Usage access settings") }
            RestrictedSettingsHelp()
        }
    }
}

@Composable
private fun ScanProgress(done: Int, total: Int) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Scanning apps… $done/$total", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        if (total <= 0) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AppsHeader(count: Int, cacheTotal: Long) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$count apps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text("Cache total: ${formatBytes(cacheTotal)}", style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortChips(selected: AppSort, onSelect: (AppSort) -> Unit) {
    ChipFlow {
        sortLabels.forEach { (sort, label) ->
            val isSelected = selected == sort
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(sort) },
                label = { Text(label) },
                leadingIcon = if (isSelected) {
                    {
                        Icon(
                            painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun AppRow(app: AppStorage, checked: Boolean, onCheckedChange: (Boolean) -> Unit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("app_row_${app.packageName}")
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag("app_check_${app.packageName}"),
        )
        AppIcon(app.packageName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(nbBytes(app.totalBytes), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
            // Number and unit are joined with a no-break space, so a wrap can only fall between the three parts.
            Text(
                "App ${nbBytes(app.appBytes)} · Data ${nbBytes(app.dataBytes)} · Cache ${nbBytes(app.cacheBytes)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Loads the icon off the main thread; shows a grey box until it is ready. */
@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    val shape = RoundedCornerShape(8.dp)
    val loaded = icon
    if (loaded != null) {
        Image(bitmap = loaded, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        Box(Modifier.size(40.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
