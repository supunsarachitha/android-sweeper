package com.supun.sweeper.scan

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

/** Schedules and cancels the scan with the platform's JobScheduler: one fixed job id, no extra libraries. */
object ScheduledScan {
    const val JOB_ID = 29001

    private fun scheduler(context: Context): JobScheduler =
        context.applicationContext.getSystemService(JobScheduler::class.java)

    /** Makes the platform job match [frequency]: schedules it, replaces it, or cancels it for Off. */
    fun apply(context: Context, frequency: ScanFrequency): Boolean {
        val js = scheduler(context)
        val plan = jobPlanFor(frequency)
        if (plan == null) {
            js.cancel(JOB_ID)
            return true
        }
        val info = JobInfo.Builder(JOB_ID, ComponentName(context.applicationContext, ScheduledScanService::class.java))
            .setPeriodic(plan.periodMillis, plan.flexMillis)
            .setRequiresCharging(plan.requiresCharging)
            .setPersisted(plan.persisted)
            .setRequiredNetworkType(if (plan.requiresNetwork) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_NONE)
            .build()
        return js.schedule(info) == JobScheduler.RESULT_SUCCESS
    }

    /** Saves [frequency] and applies it. */
    fun setFrequency(context: Context, frequency: ScanFrequency): Boolean {
        ScheduledScanStore(context).setFrequency(frequency)
        return apply(context, frequency)
    }

    /**
     * Called when the app starts: puts the job back if it is missing or has another period (an app update or a
     * cleared job list can drop it), and removes a leftover job when the setting is Off. A job that is already
     * right is left alone, so its schedule is not restarted.
     */
    fun ensureScheduled(context: Context) {
        val frequency = ScheduledScanStore(context).frequency()
        val pending = scheduler(context).getPendingJob(JOB_ID)
        val plan = jobPlanFor(frequency)
        when {
            plan == null -> if (pending != null) apply(context, frequency)
            pending == null || pending.intervalMillis != plan.periodMillis -> apply(context, frequency)
        }
    }
}
