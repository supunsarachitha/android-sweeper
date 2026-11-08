package com.supun.sweeper.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.supun.sweeper.R
import com.supun.sweeper.storage.AppSort
import com.supun.sweeper.storage.AppStorage
import com.supun.sweeper.storage.StorageRepository
import com.supun.sweeper.storage.UsageAccess
import com.supun.sweeper.storage.filterApps
import com.supun.sweeper.storage.sortApps
import com.supun.sweeper.storage.totalCache
import com.supun.sweeper.util.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val sortLabels = listOf(
    AppSort.TOTAL to "Total",
    AppSort.CACHE to "Cache",
    AppSort.DATA to "Data",
    AppSort.APP to "App size",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(resumeCount: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { StorageRepository(context) }
    // Re-checked on every resume, so returning from Usage access settings updates the screen.
    val granted = remember(resumeCount) { UsageAccess.isGranted(context) }

    var apps by remember { mutableStateOf<List<AppStorage>?>(null) }
    var progress by remember { mutableStateOf(0 to 0) }
    var sort by remember { mutableStateOf(AppSort.TOTAL) }
    var showSystem by remember { mutableStateOf(false) }

    // One scan per visit, plus one more when access has just been granted.
    LaunchedEffect(granted) {
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Apps") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val loaded = apps
            when {
                !granted -> PermissionCard(
                    onOpenSettings = { startSafely(context, UsageAccess.settingsIntent(context)) },
                )
                loaded == null -> ScanProgress(progress.first, progress.second)
                else -> {
                    val visible = remember(loaded, sort, showSystem) {
                        sortApps(filterApps(loaded, showSystem), sort)
                    }
                    AppsHeader(count = visible.size, cacheTotal = totalCache(visible))
                    SortChips(sort) { sort = it }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Show system apps", modifier = Modifier.weight(1f))
                        Switch(checked = showSystem, onCheckedChange = { showSystem = it })
                    }
                    if (visible.isEmpty()) {
                        Text("No apps to show", modifier = Modifier.padding(16.dp))
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("apps_list"),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(visible, key = { it.packageName }) { app ->
                            AppRow(app, onClick = { openAppDetails(context, app.packageName) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(onOpenSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("usage_access_card")) {
        Column(Modifier.padding(16.dp)) {
            Text("Usage access needed", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Android Sweeper needs Usage access to read how much space each app uses. " +
                    "It only reads sizes; nothing is sent anywhere (the app has no internet access).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenSettings) { Text("Open Usage access settings") }
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

@Composable
private fun SortChips(selected: AppSort, onSelect: (AppSort) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sortLabels.forEach { (sort, label) ->
            FilterChip(
                selected = selected == sort,
                onClick = { onSelect(sort) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun AppRow(app: AppStorage, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("app_row_${app.packageName}")
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "App ${formatBytes(app.appBytes)} · Data ${formatBytes(app.dataBytes)} · Cache ${formatBytes(app.cacheBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(formatBytes(app.totalBytes), style = MaterialTheme.typography.titleSmall)
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
