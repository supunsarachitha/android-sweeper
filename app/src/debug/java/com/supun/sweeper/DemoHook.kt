package com.supun.sweeper

import android.content.Context
import com.supun.sweeper.malware.SignatureStore
import com.supun.sweeper.malware.SignatureTestKit
import com.supun.sweeper.malware.SignatureVerifier
import com.supun.sweeper.storage.MalwareSignatures
import java.io.File
import java.security.MessageDigest

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
}
