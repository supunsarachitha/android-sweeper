package com.supun.sweeper.net

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureUrlTest {
    private val base = "https://raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/"

    private fun assertRejected(url: String) = assertFalse("should be rejected: $url", isAllowedSignatureUrl(url))

    @Test fun theTwoDownloadAddressesAreAllowed() {
        assertTrue(isAllowedSignatureUrl(base + "signatures.bin"))
        assertTrue(isAllowedSignatureUrl(base + "signatures.bin.sig"))
        assertTrue(isAllowedSignatureUrl(SIGNATURE_BASE_URL + SIGNATURE_DATA_FILE))
        assertTrue(isAllowedSignatureUrl(SIGNATURE_BASE_URL + SIGNATURE_SIG_FILE))
        assertEquals(base, SIGNATURE_BASE_URL)
    }

    @Test fun plainHttpIsRejected() {
        assertRejected("http://raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/signatures.bin")
    }

    @Test fun otherSchemesAreRejected() {
        assertRejected("ftp://raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/signatures.bin")
        assertRejected("file:///supunsarachitha/android-sweeper/signatures/signatures.bin")
        assertRejected("HTTPS://raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/signatures.bin")
    }

    @Test fun otherHostsAreRejected() {
        assertRejected("https://github.com/supunsarachitha/android-sweeper/signatures/signatures.bin")
        assertRejected("https://example.com/supunsarachitha/android-sweeper/signatures/signatures.bin")
        assertRejected("https://objects.githubusercontent.com/supunsarachitha/android-sweeper/signatures/signatures.bin")
    }

    @Test fun lookAlikeHostsAreRejected() {
        assertRejected("https://raw.githubusercontent.com.evil.com/supunsarachitha/android-sweeper/signatures/signatures.bin")
        assertRejected("https://evilraw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/signatures.bin")
        assertRejected("https://raw.githubusercontent.com./supunsarachitha/android-sweeper/signatures/signatures.bin")
        assertRejected("https://raw.githubusercontent.co/supunsarachitha/android-sweeper/signatures/signatures.bin")
    }

    @Test fun userInfoTricksAreRejected() {
        assertRejected("https://raw.githubusercontent.com@evil.com/")
        assertRejected("https://raw.githubusercontent.com@evil.com/supunsarachitha/android-sweeper/signatures/signatures.bin")
        assertRejected("https://user:pass@raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/signatures.bin")
        assertRejected("https://evil.com\\@raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/signatures.bin")
    }

    @Test fun otherPathsAndRepositoriesAreRejected() {
        assertRejected("https://raw.githubusercontent.com/supunsarachitha/android-sweeper/main/signatures.bin")
        assertRejected("https://raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures")
        assertRejected("https://raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/")
        assertRejected("https://raw.githubusercontent.com/supunsarachitha/other-repo/signatures/signatures.bin")
        assertRejected("https://raw.githubusercontent.com/someone-else/android-sweeper/signatures/signatures.bin")
        assertRejected("https://raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures2/signatures.bin")
    }

    @Test fun pathTraversalIsRejected() {
        assertRejected("https://raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/../main/x")
        assertRejected("https://raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/%2e%2e/main/x")
        assertRejected("https://raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/..%2fmain%2fx")
        assertRejected("https://raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/sub/signatures.bin")
        assertRejected("https://raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/..")
    }

    @Test fun portsAreRejected() {
        assertRejected("https://raw.githubusercontent.com:8443/supunsarachitha/android-sweeper/signatures/signatures.bin")
        assertRejected("https://raw.githubusercontent.com:443/supunsarachitha/android-sweeper/signatures/signatures.bin")
        assertRejected("https://raw.githubusercontent.com:80/supunsarachitha/android-sweeper/signatures/signatures.bin")
    }

    @Test fun queriesAndFragmentsAreRejected() {
        assertRejected("https://raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/signatures.bin?token=1")
        assertRejected("https://raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/signatures.bin?")
        assertRejected("https://raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/signatures.bin#x")
    }

    @Test fun garbageIsRejected() {
        assertRejected("")
        assertRejected("not a url")
        assertRejected(" $base" + "signatures.bin")
        assertRejected(base + "signatures.bin ")
        assertRejected(base + "sig natures.bin")
        assertRejected("https:raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/signatures.bin")
        assertRejected("//raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/signatures.bin")
    }

    @Test fun capAllowsExactlyTheLimit() {
        val data = ByteArray(100) { it.toByte() }
        assertEquals(100, readCapped(ByteArrayInputStream(data), 100).size)
        assertEquals(0, readCapped(ByteArrayInputStream(ByteArray(0)), 100).size)
    }

    @Test fun capStopsAnOversizedBody() {
        try {
            readCapped(ByteArrayInputStream(ByteArray(101)), 100)
            throw AssertionError("expected the cap to trigger")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("too large"))
        }
    }
}
