package com.supun.sweeper.scan

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The platform calls this while the phone charges. The work runs in a coroutine; when Android stops the job
 * (it allows about ten minutes) the run saves what it has and the job is scheduled again.
 */
class ScheduledScanService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val job = scope.launch {
            try {
                ScheduledScanRunner(applicationContext).run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed run is simply tried again at the next period.
            } catch (e: OutOfMemoryError) {
                // Same.
            }
        }
        // Only a run that ended by itself reports back; after onStopJob the system already knows.
        job.invokeOnCompletion { cause -> if (cause == null) jobFinished(params, false) }
        running = job
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true // run again later
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
