package com.supun.sweeper.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheQueueTest {
    private fun app(pkg: String, cache: Long) = AppStorage(pkg, pkg, false, 10, 10, cache)

    private val apps = listOf(app("a", 5), app("b", 50), app("c", 50), app("d", 0))

    @Test fun startOrdersByCacheAndIgnoresUnknownApps() {
        val q = CacheQueue.start(listOf("a", "c", "b", "gone", "a"), apps)!!
        assertEquals(listOf("b", "c", "a"), q.packages)
        assertEquals(105L, q.cacheBefore)
        assertEquals("b", q.current)
        assertFalse(q.waitingForReturn)
    }

    @Test fun nothingSelectedGivesNoQueue() {
        assertNull(CacheQueue.start(emptyList(), apps))
        assertNull(CacheQueue.start(listOf("gone"), apps))
    }

    @Test fun walksThroughEachAppOnce() {
        var q = CacheQueue.start(listOf("a", "b"), apps)!!
        // Coming back without having opened anything (e.g. the first resume) does not skip an app.
        q = q.returned()
        assertEquals("b", q.current)
        q = q.opened()
        assertTrue(q.waitingForReturn)
        q = q.returned()
        assertEquals("a", q.current)
        assertFalse(q.waitingForReturn)
        q = q.opened().returned()
        assertTrue(q.finished)
        assertNull(q.current)
        assertEquals(q, q.opened())
        assertEquals(q, q.returned())
    }

    @Test fun skipMovesOnOnlyWhenNoPageIsOpen() {
        val q = CacheQueue.start(listOf("a", "b"), apps)!!
        assertEquals("a", q.skip().current)
        assertEquals(q.opened(), q.opened().skip())
        assertTrue(q.skip().skip().finished)
        assertTrue(q.skip().skip().skip().finished)
    }

    @Test fun encodeDecodeRoundTrip() {
        val q = CacheQueue.start(listOf("a", "b", "c"), apps)!!.opened().returned().opened()
        assertEquals(q, CacheQueue.decode(q.encode()))
        assertNull(CacheQueue.decode(null))
        assertNull(CacheQueue.decode(""))
        assertNull(CacheQueue.decode("x|0|1|a"))
        assertNull(CacheQueue.decode("0|0|1|"))
        assertEquals(2, CacheQueue.decode("9|0|1|a,b")!!.position)
    }

    @Test fun cacheOfCountsOnlyTheGivenApps() {
        assertEquals(55L, cacheOf(listOf("a", "b", "gone"), apps))
        assertEquals(0L, cacheOf(emptyList(), apps))
    }
}
