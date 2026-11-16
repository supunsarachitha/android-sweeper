package com.supun.sweeper.net

import com.supun.sweeper.malware.DeepListSource
import com.supun.sweeper.malware.SignatureSource
import com.supun.sweeper.malware.SignedFile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

// This is the ONLY file in the app that talks to the network. CI (.github/scripts/check-network.sh) fails the
// build if networking code appears anywhere else, or if this file names any other web address.

/** The one place the app downloads from: the signed malware list on the "signatures" branch of this repository. */
const val SIGNATURE_BASE_URL = "https://raw.githubusercontent.com/supunsarachitha/android-sweeper/signatures/"

const val SIGNATURE_DATA_FILE = "signatures.bin"
const val SIGNATURE_SIG_FILE = "signatures.bin.sig"
const val MAX_SIGNATURE_BYTES = 4 * 1024
const val MAX_LIST_BYTES = 64 * 1024 * 1024

/** The second list, with samples of every file type, used by the deep scan. Same folder, same signing key. */
const val ALL_DATA_FILE = "signatures-all.bin"
const val ALL_SIG_FILE = "signatures-all.bin.sig"
const val MAX_ALL_LIST_BYTES = 256 * 1024 * 1024

/**
 * The deep-scan list as a base list plus numbered update files (same folder, same signing key). The base is
 * renewed about monthly; the update files hold the new samples since. [ALL_DATA_FILE] stays for app version 0.9.0.
 */
const val ALL_BASE_DATA_FILE = "signatures-all-base.bin"
const val ALL_BASE_SIG_FILE = "signatures-all-base.bin.sig"
const val MAX_DELTA_BYTES = 8 * 1024 * 1024
const val MAX_DELTA_NUMBER = 40

/** "signatures-all-delta-07.bin" for update file 7. */
fun deltaDataFile(seq: Int): String = String.format(Locale.ROOT, "signatures-all-delta-%02d.bin", seq)

/** "signatures-all-delta-07.bin.sig" for update file 7. */
fun deltaSigFile(seq: Int): String = deltaDataFile(seq) + ".sig"

/** The lists the app can download: the file names and the size cap of each. */
enum class SignatureList(val dataFile: String, val sigFile: String, val maxBytes: Int) {
    ANDROID(SIGNATURE_DATA_FILE, SIGNATURE_SIG_FILE, MAX_LIST_BYTES),
    ALL(ALL_DATA_FILE, ALL_SIG_FILE, MAX_ALL_LIST_BYTES),
}

private const val ALLOWED_SCHEME = "https"
private const val ALLOWED_HOST = "raw.githubusercontent.com"
private const val ALLOWED_PATH_PREFIX = "/supunsarachitha/android-sweeper/signatures/"
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 60_000
private const val HTTP_OK = 200
private const val HTTP_NOT_FOUND = 404
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

/**
 * Reads all of [input] but throws as soon as more than [maxBytes] have arrived. When the server announced the
 * size ([expectedLength], within the cap) the result is read straight into one array of that size, so a big list
 * is never held twice in memory.
 */
internal fun readCapped(input: InputStream, maxBytes: Int, expectedLength: Long = -1): ByteArray {
    if (expectedLength in 1..maxBytes.toLong()) {
        val exact = ByteArray(expectedLength.toInt())
        input.use { stream ->
            var filled = 0
            while (filled < exact.size) {
                val n = stream.read(exact, filled, exact.size - filled)
                if (n < 0) break
                filled += n
            }
            if (stream.read() >= 0) throw IOException("file too large")
            return if (filled == exact.size) exact else exact.copyOf(filled)
        }
    }
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
class SignatureDownloader(private val versionName: String) : SignatureSource, DeepListSource {
    /** The Android list (the quick scan's). */
    @Throws(IOException::class)
    override fun fetch(): SignedFile = fetch(SignatureList.ANDROID)

    @Throws(IOException::class)
    fun fetch(list: SignatureList): SignedFile {
        val sig = download(SIGNATURE_BASE_URL + list.sigFile, MAX_SIGNATURE_BYTES)
        val data = download(SIGNATURE_BASE_URL + list.dataFile, list.maxBytes)
        return SignedFile(data, sig)
    }

    /** The base list's signature file alone (71 bytes): when it differs from the stored one, a new base is out. */
    @Throws(IOException::class)
    override fun fetchBaseSig(): ByteArray = download(SIGNATURE_BASE_URL + ALL_BASE_SIG_FILE, MAX_SIGNATURE_BYTES)

    @Throws(IOException::class)
    override fun fetchBase(): SignedFile {
        val sig = download(SIGNATURE_BASE_URL + ALL_BASE_SIG_FILE, MAX_SIGNATURE_BYTES)
        val data = download(SIGNATURE_BASE_URL + ALL_BASE_DATA_FILE, MAX_ALL_LIST_BYTES)
        return SignedFile(data, sig)
    }

    /** Update file [seq] (1 to 40) with its signature; null when the server answers 404, which means "no more updates". */
    @Throws(IOException::class)
    override fun fetchDelta(seq: Int): SignedFile? {
        if (seq < 1 || seq > MAX_DELTA_NUMBER) throw IOException("no such update file")
        val data = downloadOrNull(SIGNATURE_BASE_URL + deltaDataFile(seq), MAX_DELTA_BYTES) ?: return null
        val sig = download(SIGNATURE_BASE_URL + deltaSigFile(seq), MAX_SIGNATURE_BYTES)
        return SignedFile(data, sig)
    }

    private fun download(address: String, maxBytes: Int): ByteArray =
        downloadOrNull(address, maxBytes) ?: throw IOException("server answered $HTTP_NOT_FOUND")

    /** The body of a 200 answer, null for 404, an [IOException] for anything else. */
    private fun downloadOrNull(address: String, maxBytes: Int): ByteArray? {
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
            connection.setRequestProperty("Accept-Encoding", "identity") // keeps Content-Length, so a big list is read into one array
            val code = connection.responseCode
            if (code == HTTP_NOT_FOUND) return null
            if (code != HTTP_OK) throw IOException("server answered $code")
            if (!isAllowedSignatureUrl(connection.url.toString())) throw IOException("unexpected address")
            if (connection.contentLengthLong > maxBytes) throw IOException("file too large")
            return readCapped(connection.inputStream, maxBytes, connection.contentLengthLong)
        } finally {
            connection.disconnect()
        }
    }
}
