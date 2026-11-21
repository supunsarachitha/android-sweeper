package com.supun.sweeper.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewRulesTest {
    @Test fun kindsByExtension() {
        assertEquals(PreviewKind.Image, PreviewRules.kindOf("Photo.JPG"))
        assertEquals(PreviewKind.Image, PreviewRules.kindOf("a.webp"))
        assertEquals(PreviewKind.Text, PreviewRules.kindOf("notes.txt"))
        assertEquals(PreviewKind.Text, PreviewRules.kindOf("data.json"))
        assertEquals(PreviewKind.Text, PreviewRules.kindOf("page.html"))
        assertEquals(PreviewKind.Text, PreviewRules.kindOf("conf.YAML"))
        assertEquals(PreviewKind.Pdf, PreviewRules.kindOf("doc.pdf"))
        assertEquals(PreviewKind.Video, PreviewRules.kindOf("clip.mp4"))
        assertEquals(PreviewKind.Audio, PreviewRules.kindOf("song.mp3"))
        assertEquals(PreviewKind.Apk, PreviewRules.kindOf("app.apk"))
        assertEquals(PreviewKind.Other, PreviewRules.kindOf("archive.zip"))
        assertEquals(PreviewKind.Other, PreviewRules.kindOf("noextension"))
        assertEquals(PreviewKind.Other, PreviewRules.kindOf(".jpg"))
    }

    @Test fun sizeCaps() {
        assertTrue(PreviewRules.withinSizeCap(PreviewKind.Pdf, PreviewRules.PDF_MAX_BYTES))
        assertFalse(PreviewRules.withinSizeCap(PreviewKind.Pdf, PreviewRules.PDF_MAX_BYTES + 1))
        assertTrue(PreviewRules.withinSizeCap(PreviewKind.Image, PreviewRules.IMAGE_MAX_BYTES))
        assertFalse(PreviewRules.withinSizeCap(PreviewKind.Image, PreviewRules.IMAGE_MAX_BYTES + 1))
        assertTrue(PreviewRules.withinSizeCap(PreviewKind.Video, Long.MAX_VALUE))
    }

    @Test fun smallImageIsNotDownscaled() {
        assertEquals(ImagePlan.Decode(1), PreviewRules.imagePlan(800, 600, 1080, 2400))
    }

    @Test fun bigImageIsDownscaledToAtMostTwiceTheScreen() {
        val plan = PreviewRules.imagePlan(8000, 6000, 1080, 2400) as ImagePlan.Decode
        assertTrue(plan.sampleSize > 1)
        assertEquals(0, plan.sampleSize and (plan.sampleSize - 1)) // power of two
        val decoded = (8000L / plan.sampleSize) * (6000L / plan.sampleSize)
        assertTrue(decoded <= 2L * 1080 * 2400)
        // one step less would have been over the limit
        val lessSampled = (8000L / (plan.sampleSize / 2)) * (6000L / (plan.sampleSize / 2))
        assertTrue(lessSampled > 2L * 1080 * 2400)
    }

    @Test fun absurdOrInvalidImagesAreSkipped() {
        assertEquals(ImagePlan.Skip, PreviewRules.imagePlan(20000, 20000, 1080, 2400))
        assertEquals(ImagePlan.Skip, PreviewRules.imagePlan(0, 100, 1080, 2400))
        assertEquals(ImagePlan.Skip, PreviewRules.imagePlan(-1, 100, 1080, 2400))
        assertEquals(ImagePlan.Skip, PreviewRules.imagePlan(100, 100, 0, 2400))
    }

    @Test fun pdfRenderSizeFitsTheScreenBudget() {
        val a4 = PreviewRules.pdfRenderSize(595, 842, 1080, 2400)!!
        assertEquals(1080, a4.width)
        assertEquals(1528, a4.height)
        val huge = PreviewRules.pdfRenderSize(100, 100000, 1080, 2400)!!
        assertTrue(huge.width.toLong() * huge.height <= 2L * 1080 * 2400)
        assertTrue(huge.width >= 1 && huge.height >= 1)
        assertNull(PreviewRules.pdfRenderSize(0, 842, 1080, 2400))
    }

    @Test fun textShortFileIsWholeAndNotTruncated() {
        val bytes = "hello\nworld".toByteArray()
        assertEquals(TextPreview("hello\nworld", false), PreviewRules.decodeText(bytes, bytes.size, bytes.size.toLong()))
    }

    @Test fun textIsCutAtTheLimit() {
        val bytes = ByteArray(PreviewRules.TEXT_READ_LIMIT + 500) { 'a'.code.toByte() }
        val r = PreviewRules.decodeText(bytes, bytes.size, bytes.size.toLong())!!
        assertEquals(PreviewRules.TEXT_READ_LIMIT, r.text.length)
        assertTrue(r.truncated)
    }

    @Test fun textFlagsTruncationWhenTheFileIsLargerThanWhatWasRead() {
        val bytes = "abc".toByteArray()
        assertTrue(PreviewRules.decodeText(bytes, 3, 1_000_000)!!.truncated)
    }

    @Test fun textDropsACutMultiByteCharacter() {
        // "é" is C3 A9; the read stops after C3.
        val bytes = byteArrayOf('a'.code.toByte(), 0xC3.toByte())
        val r = PreviewRules.decodeText(bytes, 2, 100)!!
        assertEquals("a", r.text)
        // a complete character at the end is kept
        val whole = byteArrayOf('a'.code.toByte(), 0xC3.toByte(), 0xA9.toByte())
        assertEquals("aé", PreviewRules.decodeText(whole, 3, 100)!!.text)
    }

    @Test fun binaryContentIsNotText() {
        assertNull(PreviewRules.decodeText(byteArrayOf(1, 2, 0, 3), 4, 4))
    }

    @Test fun durations() {
        assertEquals("0:00", PreviewRules.formatDuration(0))
        assertEquals("3:05", PreviewRules.formatDuration(185_000))
        assertEquals("1:02:03", PreviewRules.formatDuration(3_723_000))
        assertNull(PreviewRules.formatDuration(null))
        assertNull(PreviewRules.formatDuration(-5))
    }
}
