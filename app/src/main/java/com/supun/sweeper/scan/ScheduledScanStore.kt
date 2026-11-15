package com.supun.sweeper.scan

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow

/** The scheduled scan's settings and its last result, both app-private. */
class ScheduledScanStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val resultFile get() = File(appContext.filesDir, RESULT_FILE)

    /** Where the deep scan remembers file hashes between runs. */
    val hashCacheFile: File get() = File(appContext.filesDir, HASH_CACHE_FILE)

    fun frequency(): ScanFrequency = ScanFrequency.fromName(prefs.getString(KEY_FREQUENCY, null))

    fun setFrequency(frequency: ScanFrequency) {
        prefs.edit().putString(KEY_FREQUENCY, frequency.name).apply()
    }

    /** The last run, or null if there was none or the stored file is damaged. */
    fun loadResult(): ScheduledScanResult? =
        try {
            val file = resultFile
            if (!file.isFile || file.length() > MAX_RESULT_BYTES) null else ScheduledScanResultCodec.decode(file.readBytes())
        } catch (e: IOException) {
            null
        }

    fun saveResult(result: ScheduledScanResult) {
        try {
            val tmp = File(appContext.filesDir, "$RESULT_FILE.tmp").toPath()
            Files.write(tmp, ScheduledScanResultCodec.encode(result))
            try {
                Files.move(tmp, resultFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, resultFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            return // not saved; the notification still tells the user
        }
        changes.value++
    }

    /** Forgets the last result, the hash cache and the setting (tests and the debug demo). */
    fun clearAll() {
        prefs.edit().clear().apply()
        resultFile.delete()
        hashCacheFile.delete()
        changes.value++
    }

    companion object {
        private const val PREFS = "scheduled_scan"
        private const val KEY_FREQUENCY = "frequency"
        private const val RESULT_FILE = "scheduled-scan-result.bin"
        private const val HASH_CACHE_FILE = "deep-hash-cache.bin"
        private const val MAX_RESULT_BYTES = 4L * 1024 * 1024

        /** Counts saved results, so an open screen can notice a run that finished while it was showing. */
        val changes = MutableStateFlow(0)
    }
}
