package com.supun.sweeper

import android.content.Context
import com.supun.sweeper.malware.SignatureStore
import com.supun.sweeper.malware.SignatureTestKit
import com.supun.sweeper.malware.SignatureVerifier
import com.supun.sweeper.scan.DeepOutcome
import com.supun.sweeper.scan.RunStatus
import com.supun.sweeper.scan.ScanFrequency
import com.supun.sweeper.scan.ScheduledNetwork
import com.supun.sweeper.scan.ScheduledScan
import com.supun.sweeper.scan.ScheduledScanResult
import com.supun.sweeper.scan.ScheduledScanStore
import com.supun.sweeper.storage.MalwareSignatures
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * DEBUG BUILDS ONLY (this file lives in src/debug; the release build has an empty stub). Installs a demo
 * signature list for emulator screenshots. The list is signed with a key pair made at run time and checked with
 * that same key, so it proves nothing to the real verifier, which still only trusts the built-in key. The demo
 * list lives in its own folder and never touches the real stored list.
 */
object DemoHook {
    fun install(context: Context, fileHash: String?) {
        val kit = SignatureTestKit()
        val entries = ArrayList<Pair<ByteArray, String>>()
        val hash = fileHash?.lowercase()
        if (hash != null && hash.length == 64 && hash.all { it in "0123456789abcdef" }) {
            entries.add(SignatureTestKit.hexToBytes(hash) to "Demo.Banker")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        for (i in 1..40) entries.add(digest.digest("demo-sample-$i".toByteArray()) to "Demo.Filler$i")
        val (data, sig) = kit.signedFile(System.currentTimeMillis() / 1000, entries)
        val store = SignatureStore(
            File(context.filesDir, "signatures-demo"),
            verifier = SignatureVerifier(kit.publicKeyBase64),
        )
        store.clear()
        store.install(data, sig)
        MalwareSignatures.useStoreForTests(store)
    }

    /**
     * Installs a demo deep-scan list (family "Demo.Trojan (exe)" for [fileHash], plus filler entries) the same way:
     * a run-time key, its own folder, never the real stored list.
     */
    fun installDeep(context: Context, fileHash: String?) {
        val kit = SignatureTestKit()
        val entries = ArrayList<Pair<ByteArray, String>>()
        val hash = fileHash?.lowercase()
        if (hash != null && hash.length == 64 && hash.all { it in "0123456789abcdef" }) {
            entries.add(SignatureTestKit.hexToBytes(hash) to "Demo.Trojan (exe)")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        for (i in 1..400) entries.add(digest.digest("demo-deep-sample-$i".toByteArray()) to "Demo.Filler${i % 7} (pdf)")
        val (data, sig) = kit.signedFile(System.currentTimeMillis() / 1000, entries)
        val store = SignatureStore(
            File(context.filesDir, "signatures-all-demo"),
            verifier = SignatureVerifier(kit.publicKeyBase64),
            maxDataBytes = SignatureStore.MAX_ALL_DATA_BYTES,
        )
        store.clear()
        store.install(data, sig)
        MalwareSignatures.useAllStoreForTests(store)
    }

    /**
     * Schedule card demo: the schedule is on (Daily) and the last run, yesterday at 03:12, found nothing. Nothing
     * is scanned; the stored result is written directly.
     */
    fun installSchedule(context: Context) {
        val store = ScheduledScanStore(context)
        store.clearAll()
        ScheduledScan.setFrequency(context, ScanFrequency.DAILY)
        val yesterday = LocalDate.now().minusDays(1).atTime(LocalTime.of(3, 12)).atZone(ZoneId.systemDefault())
        store.saveResult(
            ScheduledScanResult(
                finishedAtMillis = yesterday.toInstant().toEpochMilli(),
                status = RunStatus.DONE,
                appsChecked = 142,
                quickFilesChecked = 12,
                deepOutcome = DeepOutcome.CHECKED,
                deepFilesChecked = 38_210,
                listUpdated = true,
                quickMatches = emptyList(),
                deepMatches = emptyList(),
            ),
        )
    }

    /**
     * Prepares a real scheduled run for the emulator script: a demo quick list that contains [fileHash], the
     * schedule on (Daily), and no network use. The script then forces the job with `cmd jobscheduler run -f`.
     */
    fun installScheduleRun(context: Context, fileHash: String?) {
        install(context, fileHash)
        ScheduledNetwork.overrideForTests(false)
        val store = ScheduledScanStore(context)
        store.clearAll()
        ScheduledScan.setFrequency(context, ScanFrequency.DAILY)
    }
}
