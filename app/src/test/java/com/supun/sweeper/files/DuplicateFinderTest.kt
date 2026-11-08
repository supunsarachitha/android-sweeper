package com.supun.sweeper.files

import java.io.File
import java.nio.file.Files
import java.util.Random
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DuplicateFinderTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var root: File
    private var now = 1_800_000_000_000L

    @Before fun setUp() {
        root = tmp.newFolder("storage").canonicalFile
    }

    private fun bytes(seed: Long, size: Int): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun file(path: String, content: ByteArray, ageDays: Int = 0): File {
        val f = File(root, path)
        f.parentFile!!.mkdirs()
        f.writeBytes(content)
        assertTrue(f.setLastModified(now - ageDays * DAY_MS))
        return f
    }

    private fun find() = DuplicateFinder(root) { now }.find()

    private fun names(g: DuplicateGroup) = g.files.map { File(it.path).name }

    @Test fun sameSizeDifferentContentIsNotGrouped() {
        file("a.bin", bytes(1, 5000))
        file("b.bin", bytes(2, 5000))
        assertEquals(0, find().groups.size)
    }

    @Test fun identicalContentInThreeFilesIsOneGroup() {
        val c = bytes(1, 5000)
        file("a.bin", c, ageDays = 1)
        file("sub/b.bin", c, ageDays = 3)
        file("sub/deep/c.bin", c, ageDays = 2)
        file("other.bin", bytes(2, 5000))
        val groups = find().groups
        assertEquals(1, groups.size)
        assertEquals(listOf("b.bin", "c.bin", "a.bin"), names(groups[0])) // oldest first
        assertEquals(5000L, groups[0].size)
        assertEquals(10000L, groups[0].wasted)
        assertEquals(64, groups[0].hash.length)
    }

    @Test fun samePrefixDifferentTailIsNotGrouped() {
        val a = bytes(1, PREFIX_BYTES + 5000)
        val b = a.copyOf()
        b[b.size - 1] = (b[b.size - 1] + 1).toByte()
        file("a.bin", a)
        file("b.bin", b)
        assertEquals(0, find().groups.size)
    }

    @Test fun differenceJustInsideThePrefixIsNotGrouped() {
        val a = bytes(1, 3000)
        val b = a.copyOf()
        b[10] = (b[10] + 1).toByte()
        file("a.bin", a)
        file("b.bin", b)
        assertEquals(0, find().groups.size)
    }

    @Test fun samePrefixSameTailIsGrouped() {
        val a = bytes(1, PREFIX_BYTES * 2 + 17)
        file("a.bin", a)
        file("b.bin", a.copyOf())
        assertEquals(1, find().groups.size)
    }

    @Test fun filesUnderOneKilobyteAreIgnored() {
        val small = bytes(1, 1023)
        file("a.bin", small)
        file("b.bin", small)
        assertEquals(0, find().groups.size)
        val exact = bytes(2, 1024)
        file("c.bin", exact)
        file("d.bin", exact)
        assertEquals(1, find().groups.size)
    }

    @Test fun skipRulesAreHonoured() {
        val c = bytes(1, 4000)
        file("Pictures/keep.bin", c)
        file("Android/data/com.x/a.bin", c)
        file("Android/obb/com.x/a.bin", c)
        file(".SweeperTrash/some-id", c)
        file(".hidden/a.bin", c)
        file("Pictures/.thumbnails/a.bin", c)
        // Android/media is not skipped
        file("Android/media/com.x/a.bin", c)
        val groups = find().groups
        assertEquals(1, groups.size)
        assertEquals(
            setOf("Pictures/keep.bin", "Android/media/com.x/a.bin"),
            groups[0].files.map { it.path.removePrefix(root.path + "/") }.toSet(),
        )
    }

    @Test fun symlinksAreNotFollowed() {
        val c = bytes(1, 4000)
        val real = file("real.bin", c)
        file("copy.bin", c)
        try {
            Files.createSymbolicLink(File(root, "link.bin").toPath(), real.toPath())
            val outside = tmp.newFolder("outside")
            File(outside, "o.bin").writeBytes(c)
            Files.createSymbolicLink(File(root, "linkdir").toPath(), outside.toPath())
        } catch (e: UnsupportedOperationException) {
            return
        }
        val groups = find().groups
        assertEquals(1, groups.size)
        assertEquals(setOf("real.bin", "copy.bin"), names(groups[0]).toSet())
    }

    @Test fun groupsAreSortedByWastedSpace() {
        val small = bytes(1, 2000)
        val big = bytes(2, 3000)
        file("s1.bin", small); file("s2.bin", small); file("s3.bin", small) // wasted 4000
        file("b1.bin", big); file("b2.bin", big) // wasted 3000
        val big2 = bytes(3, 50_000)
        file("x1.bin", big2); file("x2.bin", big2) // wasted 50000
        assertEquals(listOf(50_000L, 4000L, 3000L), find().groups.map { it.wasted })
    }

    @Test fun equalAgeIsOrderedByPath() {
        val c = bytes(1, 2000)
        file("z.bin", c); file("a.bin", c)
        assertEquals(listOf("a.bin", "z.bin"), names(find().groups[0]))
    }

    @Test fun cancellationStopsTheScan() {
        val c = bytes(1, 4000)
        for (i in 0 until 10) file("f$i.bin", c)
        var calls = 0
        try {
            DuplicateFinder(root) { now }.find(isCancelled = { ++calls > 5 })
            fail("expected cancellation")
        } catch (e: CancellationException) {
            // expected
        }
        assertTrue(calls in 6..8)
    }

    @Test fun cancellationDuringHashingStops() {
        val c = bytes(1, 3 * 1024 * 1024)
        file("a.bin", c); file("b.bin", c)
        // Let the walk and stage 2 pass, then cancel once the full hash is reading buffers.
        var stage = -1
        try {
            DuplicateFinder(root) { now }.find(
                isCancelled = { stage == STAGE_FULL },
                onProgress = { s, _, _ -> stage = s },
            )
            fail("expected cancellation")
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test fun progressReportsAllStages() {
        val c = bytes(1, 4000)
        file("a.bin", c); file("b.bin", c)
        val seen = ArrayList<Triple<Int, Int, Int>>()
        DuplicateFinder(root) { now }.find(onProgress = { s, d, t -> seen.add(Triple(s, d, t)) })
        assertEquals(setOf(STAGE_SIZES, STAGE_PREFIX, STAGE_FULL), seen.map { it.first }.toSet())
        assertTrue(seen.contains(Triple(STAGE_FULL, 2, 2)))
    }
}
