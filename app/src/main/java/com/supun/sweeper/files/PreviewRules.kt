package com.supun.sweeper.files

/** What the in-app preview shows for a file. */
enum class PreviewKind { Image, Text, Pdf, Video, Audio, Apk, Other }

/** How an image is decoded: not at all (too big to be safe) or downscaled by [sampleSize] (a power of two). */
sealed interface ImagePlan {
    data object Skip : ImagePlan
    data class Decode(val sampleSize: Int) : ImagePlan
}

/** A bitmap size, in pixels. */
data class PixelSize(val width: Int, val height: Int)

/** Text read for the preview: [text] is at most [PreviewRules.TEXT_READ_LIMIT] bytes of the file. */
data class TextPreview(val text: String, val truncated: Boolean)

/** Pure rules for the in-app preview (kind per file, size caps); no Android classes so they run in JVM tests. */
object PreviewRules {
    /** Only the first 64 KB of a text file is read. */
    const val TEXT_READ_LIMIT = 64 * 1024

    /** PDFs over 100 MB show details only. */
    const val PDF_MAX_BYTES = 100L * 1024 * 1024

    /** Images over 64 MB, or with more than 100 megapixels, show details only. */
    const val IMAGE_MAX_BYTES = 64L * 1024 * 1024
    const val IMAGE_MAX_SOURCE_PIXELS = 100_000_000L

    /** A decoded image or PDF page may have at most twice as many pixels as the screen. */
    const val SCREEN_AREA_FACTOR = 2

    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    private val TEXT = setOf("txt", "log", "json", "csv", "xml", "md", "ini", "cfg", "yaml", "yml", "html", "htm")
    private val VIDEO = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v")
    private val AUDIO = setOf("mp3", "m4a", "wav", "ogg", "flac", "aac", "opus", "amr")

    fun kindOf(name: String): PreviewKind {
        val ext = OpenRules.extensionOf(name)
        return when {
            ext in IMAGE -> PreviewKind.Image
            ext in TEXT -> PreviewKind.Text
            ext == "pdf" -> PreviewKind.Pdf
            ext in VIDEO -> PreviewKind.Video
            ext in AUDIO -> PreviewKind.Audio
            ext == "apk" -> PreviewKind.Apk
            else -> PreviewKind.Other
        }
    }

    /** False when the file is too big to preview safely for its [kind]; the preview then shows details only. */
    fun withinSizeCap(kind: PreviewKind, size: Long): Boolean = when (kind) {
        PreviewKind.Image -> size <= IMAGE_MAX_BYTES
        PreviewKind.Pdf -> size <= PDF_MAX_BYTES
        else -> true
    }

    /**
     * Decides how to decode an image whose header says [width] x [height], for a screen of [screenWidth] x
     * [screenHeight]: the decoded picture gets at most [SCREEN_AREA_FACTOR] times the screen's pixels.
     */
    fun imagePlan(width: Int, height: Int, screenWidth: Int, screenHeight: Int): ImagePlan {
        if (width <= 0 || height <= 0 || screenWidth <= 0 || screenHeight <= 0) return ImagePlan.Skip
        if (width.toLong() * height > IMAGE_MAX_SOURCE_PIXELS) return ImagePlan.Skip
        val maxPixels = SCREEN_AREA_FACTOR.toLong() * screenWidth * screenHeight
        var sample = 1
        while ((width / sample).toLong() * (height / sample) > maxPixels) sample *= 2
        return ImagePlan.Decode(sample)
    }

    /**
     * Size to render a PDF page of [pageWidth] x [pageHeight] points at: as wide as the screen, with the page's
     * proportions, shrunk if that would be more than [SCREEN_AREA_FACTOR] times the screen's pixels. Null for an
     * unusable page size.
     */
    fun pdfRenderSize(pageWidth: Int, pageHeight: Int, screenWidth: Int, screenHeight: Int): PixelSize? {
        if (pageWidth <= 0 || pageHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return null
        var w = screenWidth.toLong()
        var h = (w * pageHeight / pageWidth).coerceAtLeast(1)
        val maxPixels = SCREEN_AREA_FACTOR.toLong() * screenWidth * screenHeight
        while (w * h > maxPixels && w > 1) {
            w = (w * 9 / 10).coerceAtLeast(1)
            h = (w * pageHeight / pageWidth).coerceAtLeast(1)
        }
        return PixelSize(w.toInt(), h.toInt())
    }

    /**
     * Turns the first bytes of a file into text. [length] bytes of [bytes] are used, at most [TEXT_READ_LIMIT];
     * [fileSize] tells whether the file continues beyond them. Null when the bytes hold a NUL (a binary file,
     * not text). A multi-byte character cut by the limit is dropped.
     */
    fun decodeText(bytes: ByteArray, length: Int, fileSize: Long): TextPreview? {
        val n = length.coerceIn(0, minOf(bytes.size, TEXT_READ_LIMIT))
        for (i in 0 until n) if (bytes[i].toInt() == 0) return null
        val truncated = fileSize > n
        var end = n
        if (truncated) {
            // Step back over a UTF-8 character that the limit cut in half.
            var i = end - 1
            var back = 0
            while (i >= 0 && back < 3 && (bytes[i].toInt() and 0xC0) == 0x80) { i--; back++ }
            if (i >= 0) {
                val lead = bytes[i].toInt() and 0xFF
                val needed = when {
                    lead >= 0xF0 -> 4
                    lead >= 0xE0 -> 3
                    lead >= 0xC0 -> 2
                    else -> 1
                }
                if (needed > back + 1) end = i
            }
        }
        return TextPreview(String(bytes, 0, end, Charsets.UTF_8), truncated)
    }

    /** "3:05" or "1:02:03" for a duration in milliseconds; null if unknown or negative. */
    fun formatDuration(millis: Long?): String? {
        if (millis == null || millis < 0) return null
        val total = millis / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
