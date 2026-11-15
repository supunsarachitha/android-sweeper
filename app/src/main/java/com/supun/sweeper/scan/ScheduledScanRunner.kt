package com.supun.sweeper.scan

import android.content.Context
import com.supun.sweeper.BuildConfig
import com.supun.sweeper.malware.DeepMatch
import com.supun.sweeper.malware.FileHashCache
import com.supun.sweeper.malware.InstallResult
import com.supun.sweeper.malware.SignatureSource
import com.supun.sweeper.malware.SignatureUpdater
import com.supun.sweeper.net.SignatureDownloader
import com.supun.sweeper.net.SignatureList
import com.supun.sweeper.storage.AllFilesAccess
import com.supun.sweeper.storage.MalwareChecker
import com.supun.sweeper.storage.MalwareSignatures
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/**
 * One scheduled run: update the lists on Wi-Fi, quick scan, deep scan, store the result, show the notification.
 * Nothing is moved, trashed or uninstalled here; the findings wait on the Malware screen.
 */
class ScheduledScanRunner(context: Context) {
    private val appContext = context.applicationContext
    private val settings = ScheduledScanStore(appContext)

    /**
     * Returns the stored result, or null when the run was stopped before the quick scan finished (nothing is
     * recorded then; the job comes back later). Stopped during the deep scan, the partial result is stored and
     * shown with "Deep scan not finished", and the hash cache keeps the work done so far.
     */
    suspend fun run(): ScheduledScanResult? = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        val stopped = { job?.isActive == false }
        val result = scan(stopped) ?: return@withContext null
        // Blocking calls only from here on, so a stop request cannot cut the saving short.
        settings.saveResult(result)
        ScheduledScanNotifier(appContext).show(summaryOf(result))
        result
    }

    private suspend fun scan(stopped: () -> Boolean): ScheduledScanResult? {
        val store = MalwareSignatures.store(appContext)
        val allStore = MalwareSignatures.allStore(appContext)
        fun empty(status: RunStatus, updated: Boolean = false) = ScheduledScanResult(
            System.currentTimeMillis(), status, 0, 0, DeepOutcome.NOT_RUN, 0, updated, emptyList(), emptyList(),
        )

        if (!AllFilesAccess.isGranted(appContext)) return empty(RunStatus.NEEDS_FILES_ACCESS)

        var updated = false
        if (ScheduledNetwork.onUnmeteredNetwork(appContext)) {
            // The same two updates the Malware screen runs, with the same classes. A failure just means the
            // lists already on the phone are used.
            val downloader = SignatureDownloader(BuildConfig.VERSION_NAME)
            updated = tryUpdate { SignatureUpdater(store, downloader).update() }
            if (stopped()) throw CancellationException("Stopped")
            val allUpdated = tryUpdate { SignatureUpdater(allStore, SignatureSource { downloader.fetch(SignatureList.ALL) }).update() }
            allStore.unload() // the list can be big: do not keep it in memory
            updated = updated || allUpdated
            if (stopped()) throw CancellationException("Stopped")
        }

        val androidDb = store.current() ?: return empty(RunStatus.NO_LIST, updated)

        val checker = MalwareChecker(appContext)
        val quick = checker.scan(androidDb, includeFiles = true) { }

        var deepMatches = emptyList<DeepMatch>()
        var deepFiles = 0
        var outcome = DeepOutcome.NOT_RUN
        val allDb = try {
            allStore.current()
        } catch (e: OutOfMemoryError) {
            allStore.unload()
            null
        }
        if (allDb == null) {
            outcome = if (allStore.peekHeader() == null) DeepOutcome.SKIPPED_NO_LIST else DeepOutcome.FAILED
        } else {
            try {
                val cache = FileHashCache(
                    settings.hashCacheFile,
                    reader = OsFileStampReader,
                    minSaveIntervalMillis = CACHE_SAVE_INTERVAL_MILLIS,
                )
                var checked = 0
                val deep = checker.deepScanUntilStopped(androidDb, allDb, cache, stopped) { files, _ -> checked = files }
                deepFiles = maxOf(checked, deep.filesChecked)
                deepMatches = deep.matches
                outcome = if (deep.complete) DeepOutcome.CHECKED else DeepOutcome.UNFINISHED
            } finally {
                allStore.unload()
            }
        }

        // A file that is on both lists is one finding.
        val quickPaths = quick.matches.map { it.path }.toSet()
        return ScheduledScanResult(
            finishedAtMillis = System.currentTimeMillis(),
            status = RunStatus.DONE,
            appsChecked = quick.appsChecked,
            quickFilesChecked = quick.filesChecked,
            deepOutcome = outcome,
            deepFilesChecked = deepFiles,
            listUpdated = updated,
            quickMatches = quick.matches,
            deepMatches = deepMatches.filter { it.path !in quickPaths },
        )
    }

    /** True if the list was replaced by a newer one. Any failure counts as "not updated". */
    private fun tryUpdate(update: () -> InstallResult): Boolean =
        try {
            update() is InstallResult.Updated
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            false
        } catch (e: Exception) {
            false
        }

    private companion object {
        /** The hash cache is rewritten at most this often during a run (and always when the run stops or ends). */
        const val CACHE_SAVE_INTERVAL_MILLIS = 20_000L
    }
}
