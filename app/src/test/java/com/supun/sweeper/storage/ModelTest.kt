package com.supun.sweeper.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    private fun app(
        pkg: String,
        label: String = pkg,
        system: Boolean = false,
        a: Long = 0,
        d: Long = 0,
        c: Long = 0,
    ) = AppStorage(pkg, label, system, a, d, c)

    private val apps = listOf(
        app("p.big", "Big", a = 100, d = 10, c = 5),
        app("p.cache", "Cachey", d = 20, c = 90),
        app("p.data", "Datum", a = 5, d = 200, c = 1),
        app("p.small", "Small", a = 1, d = 1, c = 1),
    )

    private fun order(sort: AppSort) = sortApps(apps, sort).map { it.packageName }

    @Test fun totalIsSumOfParts() {
        assertEquals(115L, apps[0].totalBytes)
    }

    @Test fun sortByTotal() = assertEquals(listOf("p.data", "p.big", "p.cache", "p.small"), order(AppSort.TOTAL))

    // p.data and p.small tie on cache (1 each); "Datum" sorts before "Small".
    @Test fun sortByCache() = assertEquals(listOf("p.cache", "p.big", "p.data", "p.small"), order(AppSort.CACHE))

    @Test fun sortByData() = assertEquals(listOf("p.data", "p.cache", "p.big", "p.small"), order(AppSort.DATA))

    @Test fun sortByApp() = assertEquals(listOf("p.big", "p.data", "p.small", "p.cache"), order(AppSort.APP))

    @Test fun tiesBreakByLabelIgnoringCase() {
        val tied = listOf(app("z", "banana", a = 5), app("y", "Apple", a = 5), app("x", "cherry", a = 5))
        assertEquals(listOf("y", "z", "x"), sortApps(tied, AppSort.TOTAL).map { it.packageName })
    }

    @Test fun sortDoesNotMutateInput() {
        val copy = apps.toList()
        sortApps(apps, AppSort.APP)
        assertEquals(copy, apps)
    }

    @Test fun filterHidesSystemApps() {
        val mixed = listOf(app("u", system = false), app("s", system = true))
        assertEquals(listOf("u"), filterApps(mixed, includeSystem = false).map { it.packageName })
        assertEquals(listOf("u", "s"), filterApps(mixed, includeSystem = true).map { it.packageName })
    }

    @Test fun totalCacheSumsCache() {
        assertEquals(97L, totalCache(apps))
        assertEquals(0L, totalCache(emptyList()))
    }

    @Test fun systemAndOtherIsRemainder() {
        val b = StorageBreakdown(total = 1000, used = 600, apps = 200, images = 100, video = 50, audio = 50)
        assertEquals(200L, b.systemAndOther)
    }

    @Test fun systemAndOtherClampsToZero() {
        val b = StorageBreakdown(total = 1000, used = 300, apps = 200, images = 100, video = 50, audio = 50)
        assertEquals(0L, b.systemAndOther)
    }

    private fun sum(b: StorageBreakdown) =
        b.appsFraction + b.imagesFraction + b.videoFraction + b.audioFraction + b.systemAndOtherFraction

    @Test fun fractionsSumToAtMostOne() {
        val normal = StorageBreakdown(1000, 600, 200, 100, 50, 50)
        assertEquals(0.6f, sum(normal), 1e-5f)
        val over = StorageBreakdown(1000, 300, 800, 500, 50, 50)
        assertTrue(sum(over) <= 1f + 1e-5f)
        val empty = StorageBreakdown(0, 0, 0, 0, 0, 0)
        assertEquals(0f, sum(empty), 0f)
    }

    @Test fun volumeUsedNeverNegative() {
        assertEquals(40L, VolumeInfo(100, 60).used)
        assertEquals(0L, VolumeInfo(100, 120).used)
    }
}
