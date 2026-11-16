package com.supun.sweeper.scan

import com.supun.sweeper.malware.DeepMatch
import com.supun.sweeper.malware.MalwareMatch
import com.supun.sweeper.malware.MatchKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** How a scheduled run ended. */
enum class RunStatus {
    /** The scans ran (the deep part may still have been skipped or stopped, see [DeepOutcome]). */
    DONE,

    /** All files access is off, so nothing was scanned. */
    NEEDS_FILES_ACCESS,

    /** No Android malware list on the phone and none could be downloaded. */
    NO_LIST,
}

/** What happened to the deep scan of a run. */
enum class DeepOutcome {
    /** Every file was checked. */
    CHECKED,

    /** The run was stopped (for example by Android's time limit) before every file was checked. */
    UNFINISHED,

    /** The all-types list was never downloaded and there was no Wi-Fi. */
    SKIPPED_NO_LIST,

    /** The deep-scan list could not be loaded (for example not enough memory); tried again next time. */
    FAILED,

    /** The run did not get as far (see [RunStatus]). */
    NOT_RUN,
}

/** The last scheduled run, kept on the phone. */
data class ScheduledScanResult(
    val finishedAtMillis: Long,
    val status: RunStatus,
    val appsChecked: Int,
    /** Files the quick scan hashed (APK/DEX files). */
    val quickFilesChecked: Int,
    val deepOutcome: DeepOutcome,
    /** Files the deep scan checked (all of them when [DeepOutcome.CHECKED], so far when [DeepOutcome.UNFINISHED]). */
    val deepFilesChecked: Int,
    /** True when at least one of the two lists was replaced by a newer one during the run. */
    val listUpdated: Boolean,
    val quickMatches: List<MalwareMatch>,
    val deepMatches: List<DeepMatch>,
) {
    /** Items to review. A file on both lists counts once. */
    val findings: Int get() = quickMatches.size + deepMatches.count { d -> quickMatches.none { it.path == d.path } }
}

/**
 * The stored form of a [ScheduledScanResult]: a small versioned binary format of our own (no org.json, so it
 * works in plain JVM tests). Anything that does not parse gives null.
 */
object ScheduledScanResultCodec {
    private val MAGIC = byteArrayOf(0x53, 0x57, 0x53, 0x52, 0x31) // "SWSR1"
    private const val VERSION = 1
    private const val MAX_MATCHES = 1000

    fun encode(result: ScheduledScanResult): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.write(MAGIC)
        out.writeInt(VERSION)
        out.writeLong(result.finishedAtMillis)
        out.writeByte(result.status.ordinal)
        out.writeInt(result.appsChecked)
        out.writeInt(result.quickFilesChecked)
        out.writeByte(result.deepOutcome.ordinal)
        out.writeInt(result.deepFilesChecked)
        out.writeBoolean(result.listUpdated)
        val quick = result.quickMatches.take(MAX_MATCHES)
        out.writeInt(quick.size)
        for (m in quick) {
            out.writeByte(m.kind.ordinal)
            out.writeUTF(m.label)
            out.writeBoolean(m.packageName != null)
            if (m.packageName != null) out.writeUTF(m.packageName)
            out.writeUTF(m.path)
            out.writeUTF(m.family)
        }
        val deep = result.deepMatches.take(MAX_MATCHES)
        out.writeInt(deep.size)
        for (m in deep) {
            out.writeUTF(m.path)
            out.writeUTF(m.family)
            out.writeBoolean(m.isAndroid)
            out.writeUTF(m.sha256)
        }
        return bytes.toByteArray()
    }

    fun decode(data: ByteArray): ScheduledScanResult? =
        try {
            val input = DataInputStream(ByteArrayInputStream(data))
            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            if (!magic.contentEquals(MAGIC) || input.readInt() != VERSION) {
                null
            } else {
                val finishedAt = input.readLong()
                val status = RunStatus.entries[input.readUnsignedByte()]
                val apps = input.readInt()
                val quickFiles = input.readInt()
                val outcome = DeepOutcome.entries[input.readUnsignedByte()]
                val deepFiles = input.readInt()
                val updated = input.readBoolean()
                val quickCount = input.readInt()
                require(quickCount in 0..MAX_MATCHES && apps >= 0 && quickFiles >= 0 && deepFiles >= 0)
                val quick = List(quickCount) {
                    val kind = MatchKind.entries[input.readUnsignedByte()]
                    val label = input.readUTF()
                    val pkg = if (input.readBoolean()) input.readUTF() else null
                    MalwareMatch(kind, label, pkg, input.readUTF(), input.readUTF())
                }
                val deepCount = input.readInt()
                require(deepCount in 0..MAX_MATCHES)
                val deep = List(deepCount) {
                    DeepMatch(input.readUTF(), input.readUTF(), input.readBoolean(), input.readUTF())
                }
                if (input.read() != -1) {
                    null
                } else {
                    ScheduledScanResult(finishedAt, status, apps, quickFiles, outcome, deepFiles, updated, quick, deep)
                }
            }
        } catch (e: Exception) {
            // Truncated, wrong enum value, bad counts, bad UTF: not a result we wrote.
            null
        }
}
