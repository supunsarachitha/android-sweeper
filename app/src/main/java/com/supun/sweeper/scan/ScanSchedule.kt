package com.supun.sweeper.scan

/** How often the scheduled malware scan runs. The default is [OFF]. */
enum class ScanFrequency(val label: String) {
    OFF("Off"),
    DAILY("Daily"),
    WEEKLY("Weekly");

    companion object {
        /** The frequency saved under [name]; anything unknown counts as [OFF]. */
        fun fromName(name: String?): ScanFrequency = entries.firstOrNull { it.name == name } ?: OFF
    }
}

/** What the job needs, as plain numbers; [ScheduledScan] turns it into a platform `JobInfo`. */
data class JobPlan(
    val periodMillis: Long,
    /** The job runs in the last [flexMillis] of each period, whenever its conditions are met. */
    val flexMillis: Long,
    val requiresCharging: Boolean,
    val persisted: Boolean,
    /** False: no network is required to start (Wi-Fi is only used for the list update, when it happens to be there). */
    val requiresNetwork: Boolean,
)

private const val HOUR_MILLIS = 60L * 60 * 1000
private const val DAY_MILLIS = 24 * HOUR_MILLIS

/** The job for [frequency], or null for [ScanFrequency.OFF] (no job). */
fun jobPlanFor(frequency: ScanFrequency): JobPlan? =
    when (frequency) {
        ScanFrequency.OFF -> null
        ScanFrequency.DAILY -> JobPlan(DAY_MILLIS, 8 * HOUR_MILLIS, requiresCharging = true, persisted = true, requiresNetwork = false)
        ScanFrequency.WEEKLY -> JobPlan(7 * DAY_MILLIS, 2 * DAY_MILLIS, requiresCharging = true, persisted = true, requiresNetwork = false)
    }
