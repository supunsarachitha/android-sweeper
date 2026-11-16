package com.supun.sweeper.scan

import com.supun.sweeper.malware.DeepMatch
import com.supun.sweeper.malware.MalwareMatch
import com.supun.sweeper.malware.MatchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduledScanResultTest {
    private val app = MalwareMatch(MatchKind.APP, "Fake Bank", "com.fake.bank", "/data/app/x/base.apk", "Hydra (apk)")
    private val file = MalwareMatch(MatchKind.FILE, "update.apk", null, "/storage/emulated/0/Download/update.apk", "Cerberus (apk)")
    private val deep = DeepMatch("/storage/emulated/0/Documents/ünï cödé/dropper.exe", "AgentTesla (exe)", false, "ab".repeat(32))

    private val full = ScheduledScanResult(
        finishedAtMillis = 1_763_000_000_000L,
        status = RunStatus.DONE,
        appsChecked = 142,
        quickFilesChecked = 7,
        deepOutcome = DeepOutcome.UNFINISHED,
        deepFilesChecked = 38_210,
        listUpdated = true,
        quickMatches = listOf(app, file),
        deepMatches = listOf(deep),
    )

    @Test fun roundTripKeepsEveryField() {
        assertEquals(full, ScheduledScanResultCodec.decode(ScheduledScanResultCodec.encode(full)))
    }

    @Test fun roundTripOfEmptyResultsForEveryStatusAndOutcome() {
        for (status in RunStatus.entries) for (outcome in DeepOutcome.entries) {
            val r = full.copy(status = status, deepOutcome = outcome, quickMatches = emptyList(), deepMatches = emptyList(), listUpdated = false)
            assertEquals(r, ScheduledScanResultCodec.decode(ScheduledScanResultCodec.encode(r)))
        }
    }

    @Test fun emptyOrTruncatedInputIsNull() {
        val bytes = ScheduledScanResultCodec.encode(full)
        assertNull(ScheduledScanResultCodec.decode(ByteArray(0)))
        for (length in 0 until bytes.size) {
            assertNull("prefix of $length bytes", ScheduledScanResultCodec.decode(bytes.copyOf(length)))
        }
        assertNotNull(ScheduledScanResultCodec.decode(bytes))
    }

    @Test fun wrongMagicWrongVersionAndTrailingBytesAreNull() {
        val bytes = ScheduledScanResultCodec.encode(full)
        val badMagic = bytes.copyOf().also { it[0] = 0x00 }
        val badVersion = bytes.copyOf().also { it[8] = 0x7F } // low byte of the version int
        assertNull(ScheduledScanResultCodec.decode(badMagic))
        assertNull(ScheduledScanResultCodec.decode(badVersion))
        assertNull(ScheduledScanResultCodec.decode(bytes + byteArrayOf(0)))
    }

    @Test fun anUnknownStatusOrNegativeCountIsNull() {
        val bytes = ScheduledScanResultCodec.encode(full)
        // magic (5) + version (4) + time (8) = 17: the status byte.
        assertNull(ScheduledScanResultCodec.decode(bytes.copyOf().also { it[17] = 99 }))
        // The apps count follows (4 bytes): make it negative.
        assertNull(ScheduledScanResultCodec.decode(bytes.copyOf().also { it[18] = 0x80.toByte() }))
    }

    @Test fun randomGarbageIsNull() {
        val random = java.util.Random(1)
        repeat(50) {
            val junk = ByteArray(random.nextInt(200)).also(random::nextBytes)
            assertNull(ScheduledScanResultCodec.decode(junk))
        }
    }

    @Test fun aFileOnBothListsIsOneFinding() {
        val sameFile = DeepMatch(file.path, "Cerberus (apk)", true, "cd".repeat(32))
        val r = full.copy(quickMatches = listOf(app, file), deepMatches = listOf(sameFile, deep))
        assertEquals(3, r.findings)
    }
}
