package com.supun.sweeper.scan

import com.supun.sweeper.malware.DeepMatch
import com.supun.sweeper.malware.MalwareMatch
import com.supun.sweeper.malware.MatchKind
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScheduledScanSummaryTest {
    private val zone = ZoneId.of("UTC")
    private val at = ZonedDateTime.of(2026, 11, 14, 3, 12, 0, 0, zone).toInstant().toEpochMilli()

    private val base = ScheduledScanResult(
        finishedAtMillis = at,
        status = RunStatus.DONE,
        appsChecked = 142,
        quickFilesChecked = 12,
        deepOutcome = DeepOutcome.CHECKED,
        deepFilesChecked = 38_210,
        listUpdated = false,
        quickMatches = emptyList(),
        deepMatches = emptyList(),
    )
    private val appMatch = MalwareMatch(MatchKind.APP, "Secret Bank App", "com.secret.bank", "/data/app/base.apk", "Hydra (apk)")
    private val deepMatch = DeepMatch("/sdcard/Documents/private-name.exe", "AgentTesla (exe)", false, "ab".repeat(32))

    @Test fun nothingFoundShowsTheCounts() {
        val s = summaryOf(base)
        assertEquals("Scheduled scan: nothing found", s.title)
        assertEquals("142 apps and 38,210 files checked.", s.body)
    }

    @Test fun singularCounts() {
        val s = summaryOf(base.copy(appsChecked = 1, deepFilesChecked = 1))
        assertEquals("1 app and 1 file checked.", s.body)
    }

    @Test fun withoutADeepScanTheQuickScanFileCountIsUsed() {
        val s = summaryOf(base.copy(deepOutcome = DeepOutcome.SKIPPED_NO_LIST, deepFilesChecked = 0, quickFilesChecked = 12))
        assertEquals("142 apps and 12 files checked. $DEEP_SKIPPED_TEXT", s.body)
        assertEquals("Deep scan skipped: connect to Wi-Fi once to download the full list.", DEEP_SKIPPED_TEXT)
    }

    @Test fun findingsSayHowManyAndAskToReview() {
        val two = summaryOf(base.copy(quickMatches = listOf(appMatch), deepMatches = listOf(deepMatch)))
        assertEquals("Scheduled scan: 2 items found", two.title)
        assertEquals("Tap to review.", two.body)
        val one = summaryOf(base.copy(quickMatches = listOf(appMatch)))
        assertEquals("Scheduled scan: 1 item found", one.title)
    }

    @Test fun unfinishedDeepScanIsSaid() {
        val s = summaryOf(base.copy(deepOutcome = DeepOutcome.UNFINISHED, deepFilesChecked = 9_000))
        assertEquals("142 apps and 9,000 files checked. Deep scan not finished, continues next time.", s.body)
        val withFinding = summaryOf(base.copy(deepOutcome = DeepOutcome.UNFINISHED, quickMatches = listOf(appMatch)))
        assertEquals("Tap to review. Deep scan not finished, continues next time.", withFinding.body)
    }

    @Test fun failedDeepScanIsSaid() {
        val s = summaryOf(base.copy(deepOutcome = DeepOutcome.FAILED, deepFilesChecked = 0, quickFilesChecked = 12))
        assertEquals("142 apps and 12 files checked. $DEEP_FAILED_TEXT", s.body)
    }

    @Test fun missingFilesAccessAndMissingListHaveTheirOwnText() {
        val access = summaryOf(base.copy(status = RunStatus.NEEDS_FILES_ACCESS, deepOutcome = DeepOutcome.NOT_RUN))
        assertEquals("Scheduled scan needs All files access", access.title)
        val noList = summaryOf(base.copy(status = RunStatus.NO_LIST, deepOutcome = DeepOutcome.NOT_RUN))
        assertEquals("Scheduled scan could not run", noList.title)
        assertEquals("Connect to Wi-Fi once to download the malware list.", noList.body)
    }

    @Test fun theTextNeverNamesAnAppOrFile() {
        val s = summaryOf(base.copy(quickMatches = listOf(appMatch), deepMatches = listOf(deepMatch)))
        for (text in listOf(s.title, s.body)) {
            for (secret in listOf("Secret Bank App", "com.secret.bank", "private-name", "Hydra", "AgentTesla")) {
                assertFalse("'$text' contains $secret", text.contains(secret))
            }
        }
    }

    @Test fun lastRunLineOnTheCard() {
        assertEquals("Not run yet", lastRunLine(null, zone, Locale.US))
        assertEquals("Last run: 14 Nov, 03:12 · nothing found", lastRunLine(base, zone, Locale.US))
        assertEquals(
            "Last run: 14 Nov, 03:12 · 1 item found",
            lastRunLine(base.copy(quickMatches = listOf(appMatch)), zone, Locale.US),
        )
        assertEquals(
            "Last run: 14 Nov, 03:12 · needs All files access",
            lastRunLine(base.copy(status = RunStatus.NEEDS_FILES_ACCESS), zone, Locale.US),
        )
        assertEquals("14 Nov", formatRunDate(at, zone, Locale.US))
    }
}
