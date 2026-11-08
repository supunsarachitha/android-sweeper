package com.supun.sweeper.net

import com.supun.sweeper.malware.SignatureSource
import com.supun.sweeper.malware.SignedFile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

// This is the ONLY file in the app that talks to the network. CI (.github/scripts/check-network.sh) fails the
// build if networking code appears anywhere else, or if this file names any other web address.

/** The one place the app downloads from: the signed malware list on the "signatures" branch of this repository. */
const val SIGNATURE_BASE_URL = "https://raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/"

const val SIGNATURE_DATA_FILE = "signatures.bin"
const val SIGNATURE_SIG_FILE = "signatures.bin.sig"
const val MAX_SIGNATURE_BYTES = 4 * 1024
const val MAX_LIST_BYTES = 64 * 1024 * 1024

private const val ALLOWED_SCHEME = "https"
private const val ALLOWED_HOST = "raw.githubusercontent.com"
private const val ALLOWED_PATH_PREFIX = "/supunsarachitha/android-sweeper/signatures/"
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 60_000
private const val HTTP_OK = 200
private val FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

/**
 * True only for an address inside the fixed download location: scheme https, host exactly the one above (no
 * user info, no port), a path directly below the signatures folder with a plain file name, no query, no fragment.
 */
fun isAllowedSignatureUrl(url: String): Boolean {
    val uri = try {
        URI(url)
    } catch (e: URISyntaxException) {
        return false
    }
    if (uri.scheme != ALLOWED_SCHEME) return false
    if (uri.rawAuthority != ALLOWED_HOST) return false // rejects user info, ports and look-alike hosts
    if (uri.rawQuery != null || uri.rawFragment != null) return false
    val path = uri.rawPath ?: return false
    if (!path.startsWith(ALLOWED_PATH_PREFIX)) return false
    return FILE_NAME.matches(path.substring(ALLOWED_PATH_PREFIX.length)) // no slashes, no "..", no escapes
}

/** Reads all of [input] but throws as soon as more than [maxBytes] have arrived. */
internal fun readCapped(input: InputStream, maxBytes: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    input.use { stream ->
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            if (out.size().toLong() + n > maxBytes) throw IOException("file too large")
            out.write(buffer, 0, n)
        }
    }
    return out.toByteArray()
}

/**
 * Downloads the signature list and its signature. Nothing is trusted here: the caller verifies the signature.
 * Redirects are not followed (any answer other than 200 fails), so a request can never be sent elsewhere.
 */
class SignatureDownloader(private val versionName: String) : SignatureSource {
    @Throws(IOException::class)
    override fun fetch(): SignedFile {
        val sig = download(SIGNATURE_BASE_URL + SIGNATURE_SIG_FILE, MAX_SIGNATURE_BYTES)
        val data = download(SIGNATURE_BASE_URL + SIGNATURE_DATA_FILE, MAX_LIST_BYTES)
        return SignedFile(data, sig)
    }

    private fun download(address: String, maxBytes: Int): ByteArray {
        if (!isAllowedSignatureUrl(address)) throw IOException("address not allowed")
        val connection = URL(address).openConnection() as? HttpsURLConnection
            ?: throw IOException("not a secure connection")
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "AndroidSweeper/$versionName")
            val code = connection.responseCode
            if (code != HTTP_OK) throw IOException("server answered $code")
            if (!isAllowedSignatureUrl(connection.url.toString())) throw IOException("unexpected address")
            if (connection.contentLengthLong > maxBytes) throw IOException("file too large")
            return readCapped(connection.inputStream, maxBytes)
        } finally {
            connection.disconnect()
        }
    }
}
