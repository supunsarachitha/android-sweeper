package com.supun.sweeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supun.sweeper.R
import com.supun.sweeper.malware.formatCount
import com.supun.sweeper.malware.formatSignatureDate
import com.supun.sweeper.storage.DuplicateStats
import com.supun.sweeper.storage.FileCleaner
import com.supun.sweeper.storage.JunkStats
import com.supun.sweeper.storage.MalwareSignatures
import com.supun.sweeper.storage.StorageBreakdown
import com.supun.sweeper.storage.StorageRepository
import com.supun.sweeper.storage.TrashSummary
import com.supun.sweeper.storage.UsageAccess
import com.supun.sweeper.storage.VolumeInfo
import com.supun.sweeper.util.usedFraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Segment(val label: String, val bytes: Long, val fraction: Float, val color: Color)

// Breakdown colours: the first set for a light background, the second (lighter) set for dark mode.
private val AppsColor = Color(0xFF0F766E)
private val ImagesColor = Color(0xFFD97706)
private val VideoColor = Color(0xFF2563EB)
private val AudioColor = Color(0xFFDB2777)
private val OtherColor = Color(0xFF64748B)
private val AppsColorDark = Color(0xFF5EEAD4)
private val ImagesColorDark = Color(0xFFFBBF24)
private val VideoColorDark = Color(0xFF7CB0FF)
private val AudioColorDark = Color(0xFFF59EC4)
private val OtherColorDark = Color(0xFFB4C0D0)

@Composable
fun HomeScreen(
    resumeCount: Int,
    onOpenApps: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onOpenJunk: () -> Unit,
    onOpenMalware: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { StorageRepository(context) }
    var volume by remember { mutableStateOf(StorageRepository.statFsVolume()) }
    var breakdown by remember { mutableStateOf<StorageBreakdown?>(null) }
    // Re-checked every time the app comes back to the front (e.g. from Usage access settings).
    val granted = remember(resumeCount) { UsageAccess.isGranted(context) }

    val cleaner = remember { FileCleaner(context) }
    var trashSummary by remember { mutableStateOf(TrashSummary(0, 0)) }

    // The signature date and count for the Malware card; null until the stored list has been read.
    var signatureLine by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resumeCount) {
        signatureLine = withContext(Dispatchers.IO) {
            val list = MalwareSignatures.store(context).current()
            val deepList = MalwareSignatures.allStore(context).peekHeader() // header only: the list can be big
            val line = if (list == null) {
                "No signatures yet — tap to download"
            } else {
                "Signatures from ${formatSignatureDate(list.createdAt)} · ${formatCount(list.count)} known samples"
            }
            if (deepList == null) line else "$line · deep scan list: ${formatSignatureDate(deepList.createdAt)}"
        }
    }

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
        topBar = { SweeperTopBar("Android Sweeper") },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("home_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StorageCard(volume) }
            item {
                if (granted) {
                    BreakdownCard(breakdown)
                } else {
                    GrantPrompt(onGrant = { startSafely(context, UsageAccess.settingsIntent(context)) })
                }
            }
            item { SectionHeader("Clean up") }
            item { AppsCard(onOpenApps) }
            item { FilesCard(onOpenFiles) }
            item { DuplicatesCard(DuplicateStats.wastedBytes, onOpenDuplicates) }
            item { JunkCard(JunkStats.junkBytes, onOpenJunk) }
            item { TrashCard(trashSummary, onOpenTrash) }
            item { SectionHeader("Protect") }
            item { MalwareCard(signatureLine, onOpenMalware) }
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
        Column(Modifier.padding(20.dp)) {
            Text("Internal storage", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold)) { append(nbBytes(volume.used)) }
                    append(" used of ${nbBytes(volume.total)}")
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(16.dp))
            // A plain rounded bar: the Material progress bar adds a dot at its end.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(usedFraction(volume.used, volume.total).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("${nbBytes(volume.free)} free", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun GrantPrompt(onGrant: () -> Unit) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Grant usage access to see what uses your space",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Button(onClick = onGrant) { Text("Grant") }
        }
    }
}

@Composable
private fun BreakdownCard(breakdown: StorageBreakdown?) {
    val dark = isSystemInDarkTheme()
    Card(modifier = Modifier.fillMaxWidth(), colors = sweeperCardColors()) {
        Column(Modifier.padding(16.dp)) {
            Text("What uses your space", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            if (breakdown == null) {
                Text("Calculating…", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            val segments = listOf(
                Segment("Apps", breakdown.apps, breakdown.appsFraction, if (dark) AppsColorDark else AppsColor),
                Segment("Images", breakdown.images, breakdown.imagesFraction, if (dark) ImagesColorDark else ImagesColor),
                Segment("Video", breakdown.video, breakdown.videoFraction, if (dark) VideoColorDark else VideoColor),
                Segment("Audio", breakdown.audio, breakdown.audioFraction, if (dark) AudioColorDark else AudioColor),
                Segment(
                    "System & other",
                    breakdown.systemAndOther,
                    breakdown.systemAndOtherFraction,
                    if (dark) OtherColorDark else OtherColor,
                ),
            )
            val freeWeight = 1f - segments.sumOf { it.fraction.toDouble() }.toFloat()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
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
                    Text(nbBytes(segment.bytes), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun AppsCard(onClick: () -> Unit) {
    ToolCard(
        icon = R.drawable.ic_apps,
        title = "Apps",
        subtitle = "See every app's size and cache",
        onClick = onClick,
        modifier = Modifier.testTag("apps_card"),
    )
}

@Composable
private fun FilesCard(onClick: () -> Unit) {
    ToolCard(
        icon = R.drawable.ic_file,
        title = "Large & old files",
        subtitle = "Find big files, old downloads and leftover APKs.",
        onClick = onClick,
        modifier = Modifier.testTag("files_card"),
    )
}

@Composable
private fun DuplicatesCard(wastedBytes: Long?, onClick: () -> Unit) {
    ToolCard(
        icon = R.drawable.ic_copy,
        title = "Duplicates",
        subtitle = "Find identical copies of the same file",
        onClick = onClick,
        modifier = Modifier.testTag("duplicates_card"),
        highlight = wastedBytes?.let { "${nbBytes(it)} in duplicates" },
    )
}

@Composable
private fun JunkCard(junkBytes: Long?, onClick: () -> Unit) {
    ToolCard(
        icon = R.drawable.ic_sparkle,
        title = "Junk cleanup",
        subtitle = "Empty folders, temp files and thumbnail caches.",
        onClick = onClick,
        modifier = Modifier.testTag("junk_card"),
        highlight = junkBytes?.let { "${nbBytes(it)} of junk" },
    )
}

@Composable
private fun TrashCard(summary: TrashSummary, onClick: () -> Unit) {
    val items = if (summary.count == 1) "1 item" else "${summary.count} items"
    ToolCard(
        icon = R.drawable.ic_trash,
        title = "Trash",
        subtitle = "$items · emptied automatically after 7 days",
        onClick = onClick,
        modifier = Modifier.testTag("trash_card"),
        badge = if (summary.count > 0) nbBytes(summary.bytes) else null,
    )
}

@Composable
private fun MalwareCard(signatureLine: String?, onClick: () -> Unit) {
    ToolCard(
        icon = R.drawable.ic_shield,
        title = "Malware check",
        subtitle = "Compare apps and APK files with a list of known Android malware.",
        onClick = onClick,
        modifier = Modifier.testTag("malware_card"),
        highlight = signatureLine,
    )
}
