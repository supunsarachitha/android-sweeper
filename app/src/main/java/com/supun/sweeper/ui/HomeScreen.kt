package com.supun.sweeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.supun.sweeper.storage.DuplicateStats
import com.supun.sweeper.storage.FileCleaner
import com.supun.sweeper.storage.StorageBreakdown
import com.supun.sweeper.storage.StorageRepository
import com.supun.sweeper.storage.TrashSummary
import com.supun.sweeper.storage.UsageAccess
import com.supun.sweeper.storage.VolumeInfo
import com.supun.sweeper.util.formatBytes
import com.supun.sweeper.util.usedFraction
import kotlinx.coroutines.CancellationException

private data class Feature(val title: String, val description: String)

private val upcoming = listOf(
    Feature("Junk cleanup", "Empty folders, temp files and thumbnail caches."),
)

private data class Segment(val label: String, val bytes: Long, val fraction: Float, val color: Color)

private val AppsColor = Color(0xFF0F766E)
private val ImagesColor = Color(0xFFF59E0B)
private val VideoColor = Color(0xFF3B82F6)
private val AudioColor = Color(0xFFEC4899)
private val OtherColor = Color(0xFF94A3B8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    resumeCount: Int,
    onOpenApps: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenDuplicates: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { StorageRepository(context) }
    var volume by remember { mutableStateOf(StorageRepository.statFsVolume()) }
    var breakdown by remember { mutableStateOf<StorageBreakdown?>(null) }
    // Re-checked every time the app comes back to the front (e.g. from Usage access settings).
    val granted = remember(resumeCount) { UsageAccess.isGranted(context) }

    val cleaner = remember { FileCleaner(context) }
    var trashSummary by remember { mutableStateOf(TrashSummary(0, 0)) }

    LaunchedEffect(Unit) { volume = repo.internalVolume() }
    // Refreshed whenever Home is shown again (e.g. back from the Trash screen).
    LaunchedEffect(resumeCount) {
        trashSummary = try {
            cleaner.trashSummary()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrashSummary(0, 0)
        }
    }
    LaunchedEffect(granted) {
        breakdown = if (granted) repo.breakdown() else null
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Android Sweeper") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StorageCard(volume) }
            item {
                if (granted) {
                    BreakdownCard(breakdown)
                } else {
                    GrantRow(onGrant = { startSafely(context, UsageAccess.settingsIntent(context)) })
                }
            }
            item { AppsCard(onOpenApps) }
            item { FilesCard(onOpenFiles) }
            item { DuplicatesCard(DuplicateStats.wastedBytes, onOpenDuplicates) }
            item { TrashCard(trashSummary, onOpenTrash) }
            items(upcoming) { FeatureCard(it) }
            item {
                TextButton(onClick = { openStorageSettings(context) }) {
                    Text("Open Android storage settings")
                }
            }
        }
    }
}

@Composable
private fun StorageCard(volume: VolumeInfo) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("storage_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Internal storage", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "${formatBytes(volume.used)} used of ${formatBytes(volume.total)}",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { usedFraction(volume.used, volume.total) },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text("${formatBytes(volume.free)} free", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun GrantRow(onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Grant usage access to see what uses your space",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = onGrant) { Text("Grant") }
    }
}

@Composable
private fun BreakdownCard(breakdown: StorageBreakdown?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("What uses your space", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (breakdown == null) {
                Text("Calculating…", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            val segments = listOf(
                Segment("Apps", breakdown.apps, breakdown.appsFraction, AppsColor),
                Segment("Images", breakdown.images, breakdown.imagesFraction, ImagesColor),
                Segment("Video", breakdown.video, breakdown.videoFraction, VideoColor),
                Segment("Audio", breakdown.audio, breakdown.audioFraction, AudioColor),
                Segment("System & other", breakdown.systemAndOther, breakdown.systemAndOtherFraction, OtherColor),
            )
            val freeWeight = 1f - segments.sumOf { it.fraction.toDouble() }.toFloat()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .testTag("breakdown_bar"),
            ) {
                segments.forEach { segment ->
                    if (segment.fraction > 0f) {
                        Box(
                            Modifier
                                .weight(segment.fraction)
                                .fillMaxHeight()
                                .background(segment.color),
                        )
                    }
                }
                if (freeWeight > 0.0001f) Spacer(Modifier.weight(freeWeight))
            }
            Spacer(Modifier.height(12.dp))
            segments.forEach { segment ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(segment.color))
                    Spacer(Modifier.width(8.dp))
                    Text(segment.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(formatBytes(segment.bytes), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun AppsCard(onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("apps_card")) {
        Column(Modifier.padding(16.dp)) {
            Text("Apps", style = MaterialTheme.typography.titleMedium)
            Text("See every app's size and cache", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun FilesCard(onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("files_card")) {
        Column(Modifier.padding(16.dp)) {
            Text("Large & old files", style = MaterialTheme.typography.titleMedium)
            Text("Find big files, old downloads and leftover APKs.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DuplicatesCard(wastedBytes: Long?, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("duplicates_card")) {
        Column(Modifier.padding(16.dp)) {
            Text("Duplicates", style = MaterialTheme.typography.titleMedium)
            Text("Find identical copies of the same file", style = MaterialTheme.typography.bodyMedium)
            if (wastedBytes != null) {
                Text(
                    "${formatBytes(wastedBytes)} in duplicates",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun TrashCard(summary: TrashSummary, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("trash_card")) {
        Column(Modifier.padding(16.dp)) {
            Text("Trash", style = MaterialTheme.typography.titleMedium)
            val items = if (summary.count == 1) "1 item" else "${summary.count} items"
            Text(
                "$items · ${formatBytes(summary.bytes)} · emptied automatically after 7 days",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun FeatureCard(feature: Feature) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(feature.title, style = MaterialTheme.typography.titleMedium)
            Text(feature.description, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Coming soon",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
