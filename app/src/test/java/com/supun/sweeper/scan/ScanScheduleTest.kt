package com.supun.sweeper.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanScheduleTest {
    private val day = 24L * 60 * 60 * 1000

    @Test fun offHasNoJob() {
        assertNull(jobPlanFor(ScanFrequency.OFF))
    }

    @Test fun dailyRunsEveryDayWhileChargingAndKeepsThroughRestarts() {
        val plan = jobPlanFor(ScanFrequency.DAILY)!!
        assertEquals(day, plan.periodMillis)
        assertTrue(plan.requiresCharging)
        assertTrue(plan.persisted)
        assertFalse("no network is required to start", plan.requiresNetwork)
    }

    @Test fun weeklyRunsEverySevenDays() {
        val plan = jobPlanFor(ScanFrequency.WEEKLY)!!
        assertEquals(7 * day, plan.periodMillis)
        assertTrue(plan.requiresCharging)
        assertTrue(plan.persisted)
        assertFalse(plan.requiresNetwork)
    }

    @Test fun flexIsAWindowInsideThePeriodAndAtLeastTheSystemMinimum() {
        for (frequency in listOf(ScanFrequency.DAILY, ScanFrequency.WEEKLY)) {
            val plan = jobPlanFor(frequency)!!
            assertTrue(plan.flexMillis in (5 * 60 * 1000L)..plan.periodMillis)
        }
    }

    @Test fun savedNamesComeBackAndUnknownMeansOff() {
        for (frequency in ScanFrequency.entries) assertEquals(frequency, ScanFrequency.fromName(frequency.name))
        assertEquals(ScanFrequency.OFF, ScanFrequency.fromName(null))
        assertEquals(ScanFrequency.OFF, ScanFrequency.fromName("HOURLY"))
        assertEquals(ScanFrequency.OFF, ScanFrequency.fromName(""))
    }

    @Test fun labelsAreWhatTheCardShows() {
        assertEquals(listOf("Off", "Daily", "Weekly"), ScanFrequency.entries.map { it.label })
    }
}
