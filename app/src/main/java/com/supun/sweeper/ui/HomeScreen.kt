package com.supun.sweeper.ui

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.supun.sweeper.util.formatBytes
import com.supun.sweeper.util.usedFraction

data class StorageSnapshot(val total: Long, val free: Long) {
    val used: Long get() = total - free
}

/** Internal shared storage (the /data partition, which also backs /sdcard on modern phones). */
fun readInternalStorage(): StorageSnapshot {
    val stat = StatFs(Environment.getDataDirectory().path)
    return StorageSnapshot(total = stat.totalBytes, free = stat.availableBytes)
}

private data class Feature(val title: String, val description: String)

private val upcoming = listOf(
    Feature("Storage overview", "See what uses your space, app by app."),
    Feature("Large & old files", "Find big files, old downloads and leftover APKs."),
    Feature("Duplicates", "Find identical copies of the same file."),
    Feature("Junk cleanup", "Empty folders, temp files and thumbnail caches."),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val storage = remember { readInternalStorage() }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Android Sweeper") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StorageCard(storage) }
            items(upcoming) { FeatureCard(it) }
        }
    }
}

@Composable
private fun StorageCard(storage: StorageSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("storage_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Internal storage", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "${formatBytes(storage.used)} used of ${formatBytes(storage.total)}",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { usedFraction(storage.used, storage.total) },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text("${formatBytes(storage.free)} free", style = MaterialTheme.typography.bodyMedium)
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
