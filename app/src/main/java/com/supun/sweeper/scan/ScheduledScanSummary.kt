package com.supun.sweeper.scan

import com.supun.sweeper.malware.formatCount
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Notification text. Counts only: never a file or app name, because it can show on the lock screen. */
data class ScanSummary(val title: String, val body: String)

private fun apps(n: Int) = if (n == 1) "1 app" else "${formatCount(n)} apps"
private fun files(n: Int) = if (n == 1) "1 file" else "${formatCount(n)} files"
private fun items(n: Int) = if (n == 1) "1 item" else "${formatCount(n)} items"

const val DEEP_UNFINISHED_TEXT = "Deep scan not finished, continues next time."
const val DEEP_FAILED_TEXT = "Deep scan could not run, will try again next time."
const val DEEP_SKIPPED_TEXT = "Deep scan skipped: connect to Wi-Fi once to download the full list."

/** The summary notification for [result]. */
fun summaryOf(result: ScheduledScanResult): ScanSummary =
    when (result.status) {
        RunStatus.NEEDS_FILES_ACCESS -> ScanSummary(
            "Scheduled scan needs All files access",
            "Open Android Sweeper to allow it.",
        )
        RunStatus.NO_LIST -> ScanSummary(
            "Scheduled scan could not run",
            "Connect to Wi-Fi once to download the malware list.",
        )
        RunStatus.DONE -> {
            val found = result.findings
            val deepRan = result.deepOutcome == DeepOutcome.CHECKED || result.deepOutcome == DeepOutcome.UNFINISHED
            val fileCount = if (deepRan) result.deepFilesChecked else result.quickFilesChecked
            val base = if (found == 0) "${apps(result.appsChecked)} and ${files(fileCount)} checked." else "Tap to review."
            val extra = when (result.deepOutcome) {
                DeepOutcome.UNFINISHED -> " $DEEP_UNFINISHED_TEXT"
                DeepOutcome.SKIPPED_NO_LIST -> " $DEEP_SKIPPED_TEXT"
                DeepOutcome.FAILED -> " $DEEP_FAILED_TEXT"
                else -> ""
            }
            ScanSummary(
                if (found == 0) "Scheduled scan: nothing found" else "Scheduled scan: ${items(found)} found",
                base + extra,
            )
        }
    }

/** "14 Nov, 03:12" */
fun formatRunTime(millis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("d MMM, HH:mm", locale).format(Instant.ofEpochMilli(millis).atZone(zone))

/** "14 Nov" */
fun formatRunDate(millis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("d MMM", locale).format(Instant.ofEpochMilli(millis).atZone(zone))

/** The line on the card: "Last run: 14 Nov, 03:12 · nothing found", or "Not run yet" when [result] is null. */
fun lastRunLine(result: ScheduledScanResult?, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    if (result == null) return "Not run yet"
    val what = when (result.status) {
        RunStatus.NEEDS_FILES_ACCESS -> "needs All files access"
        RunStatus.NO_LIST -> "no malware list on the phone yet"
        RunStatus.DONE -> if (result.findings == 0) "nothing found" else "${items(result.findings)} found"
    }
    return "Last run: ${formatRunTime(result.finishedAtMillis, zone, locale)} · $what"
}
