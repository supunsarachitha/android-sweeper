package com.supun.sweeper.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supun.sweeper.files.OpenRules
import com.supun.sweeper.files.PreviewKind
import com.supun.sweeper.files.PreviewRules
import com.supun.sweeper.storage.FileOpener
import com.supun.sweeper.storage.FilePreviewer
import com.supun.sweeper.storage.PreviewContent
import com.supun.sweeper.storage.storageRoot
import com.supun.sweeper.util.formatBytes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What a list screen hands over when a row is tapped. The list stays on screen underneath the preview, so
 * the selection state is shared: [isSelected] reads the list's own state and [onToggle] changes it.
 * [canSelect] is false for the one copy of a duplicate group that has to stay: no Select, no Move to trash.
 */
class PreviewSession(
    val path: String,
    val canSelect: () -> Boolean,
    val isSelected: () -> Boolean,
    val onToggle: () -> Unit,
    /** Moves just this file to the trash (the list does the move, shows "Undo" and refreshes itself). */
    val onTrash: () -> Unit,
)

private class Details(val size: Long, val modified: Long)

private fun typeLabel(name: String): String {
    val ext = OpenRules.extensionOf(name).uppercase()
    val withExt = { kind: String -> if (ext.isEmpty()) kind else "$kind ($ext)" }
    return when (PreviewRules.kindOf(name)) {
        PreviewKind.Image -> withExt("Image")
        PreviewKind.Text -> withExt("Text file")
        PreviewKind.Pdf -> "PDF document"
        PreviewKind.Video -> withExt("Video")
        PreviewKind.Audio -> withExt("Audio")
        PreviewKind.Apk -> "Android app (APK)"
        PreviewKind.Other -> if (ext.isEmpty()) "File" else "$ext file"
    }
}

/** Full-screen, read-only preview of one file with its details and actions. */
@Composable
fun PreviewScreen(session: PreviewSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val path = session.path
    val name = remember(path) { File(path).name }
    val rootPath = remember { storageRoot().canonicalPath }
    val metrics = context.resources.displayMetrics
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmTrash by remember { mutableStateOf(false) }
    var confirmInstall by remember { mutableStateOf(false) }

    val details by produceState<Details?>(null, path) {
        value = withContext(Dispatchers.IO) {
            val f = File(path)
            Details(f.length(), f.lastModified())
        }
    }
    val content by produceState<PreviewContent?>(null, path) {
        value = FilePreviewer.load(context, path, metrics.widthPixels, metrics.heightPixels)
    }
    val isApk = OpenRules.isApk(name)
    val canSelect = session.canSelect()
    val selected = session.isSelected()

    Scaffold(
        modifier = Modifier.testTag("preview_screen"),
        topBar = { SweeperTopBar(name, onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(Modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 3.dp) {
                Column(
                    Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isApk) {
                        Button(
                            onClick = { confirmInstall = true },
                            modifier = Modifier.fillMaxWidth().testTag("preview_install_button"),
                        ) { Text("Install") }
                    } else {
                        Button(
                            onClick = {
                                FileOpener.open(context, path).message?.let { msg -> scope.launch { snackbar.showSnackbar(msg) } }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("preview_open_button"),
                        ) { Text("Open with…") }
                    }
                    if (canSelect) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { session.onToggle() },
                                modifier = Modifier.weight(1f).testTag("preview_select_button"),
                            ) { Text(if (selected) "Deselect" else "Select", maxLines = 1) }
                            OutlinedButton(
                                onClick = { confirmTrash = true },
                                modifier = Modifier.weight(1f).testTag("preview_trash_button"),
                            ) { Text("Move to trash", maxLines = 1) }
                        }
                    } else {
                        Text(
                            "This is the copy that stays. Duplicates always keep one copy, so it can't be selected or trashed.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("preview_keep_note"),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PreviewBody(content, path)

            Card(Modifier.fillMaxWidth().testTag("preview_details"), colors = sweeperCardColors()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailLine("Name", name)
                    DetailLine("Folder", relativeFolder(rootPath, path))
                    DetailLine("Size", details?.let { nbBytes(it.size) } ?: "…")
                    DetailLine("Modified", details?.let { formatDate(it.modified) } ?: "…")
                    DetailLine("Type", typeLabel(name))
                }
            }
        }
    }

    if (confirmInstall) {
        InstallConfirmDialog(
            onCancel = { confirmInstall = false },
            onInstall = {
                confirmInstall = false
                FileOpener.open(context, path).message?.let { msg -> scope.launch { snackbar.showSnackbar(msg) } }
            },
        )
    }

    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text("Move to trash?") },
            text = {
                Text(
                    "Move this file (${formatBytes(details?.size ?: 0L)}) to the trash? You can restore it for 7 days.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmTrash = false
                        session.onTrash()
                        onBack()
                    },
                    modifier = Modifier.testTag("preview_confirm_button"),
                ) { Text("Move to trash") }
            },
            dismissButton = { TextButton(onClick = { confirmTrash = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PreviewBody(content: PreviewContent?, path: String) {
    val box = Modifier.fillMaxWidth()
    when (content) {
        null -> Box(box.height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is PreviewContent.Image -> Image(
            bitmap = content.bitmap.asImageBitmap(),
            contentDescription = "Preview of ${File(path).name}",
            contentScale = ContentScale.Fit,
            modifier = box.heightIn(max = 260.dp).clip(RoundedCornerShape(12.dp)).testTag("preview_image"),
        )
        is PreviewContent.Pdf -> Column(box, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(
                bitmap = content.page.asImageBitmap(),
                contentDescription = "First page of ${File(path).name}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 210.dp).testTag("preview_pdf"),
            )
            Text(
                if (content.pageCount == 1) "1 page, first page shown" else "${content.pageCount} pages, first page shown",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is PreviewContent.Text -> Column(box, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
                    .testTag("preview_text"),
            ) {
                Text(
                    content.text.ifEmpty { "(empty file)" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
            if (content.truncated) {
                Text(
                    "Showing the first ${PreviewRules.TEXT_READ_LIMIT / 1024} KB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is PreviewContent.Video -> Column(box, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content.frame?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "A frame of ${File(path).name}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(12.dp)).testTag("preview_video"),
                )
            }
            PreviewRules.formatDuration(content.durationMillis)?.let { Text("Duration $it", style = MaterialTheme.typography.titleSmall) }
        }
        is PreviewContent.Audio -> Column(box, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(content.title ?: File(path).nameWithoutExtension, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("preview_audio"))
            content.artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            PreviewRules.formatDuration(content.durationMillis)?.let { Text("Duration $it", style = MaterialTheme.typography.bodyMedium) }
        }
        is PreviewContent.Apk -> Row(box.testTag("preview_apk"), verticalAlignment = Alignment.CenterVertically) {
            content.icon?.let {
                Image(it.asImageBitmap(), contentDescription = "App icon", modifier = Modifier.size(72.dp))
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(content.label, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(content.packageName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                val version = if (content.versionName.isBlank()) "" else "${content.versionName} · "
                Text("Version $version" + "build ${content.versionCode}", style = MaterialTheme.typography.bodyMedium)
                if (content.installed) {
                    Text("This version or a newer one is already installed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        is PreviewContent.DetailsOnly -> content.reason?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = box.testTag("preview_details_only"))
        } ?: Text(
            "No preview for this type of file.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = box.testTag("preview_details_only"),
        )
        is PreviewContent.Failed -> Text(
            content.message,
            style = MaterialTheme.typography.titleMedium,
            modifier = box.testTag("preview_failed"),
        )
    }
}
