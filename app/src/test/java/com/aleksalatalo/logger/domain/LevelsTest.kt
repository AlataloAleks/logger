package com.aleksalatalo.logger.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LevelsTest {
    @Test
    fun thresholdsFollowTheCurve() {
        assertEquals(0L, secondsForLevel(1))
        assertEquals(3674L, secondsForLevel(2)) // about 1 hour
        assertEquals(10_000L * 3600, secondsForLevel(100))
        assertEquals(0, daysForLevel(1))
        assertEquals(1, daysForLevel(2))
        assertEquals(31, daysForLevel(10))
        assertEquals(3650, daysForLevel(100))
    }

    @Test
    fun levelIsExactAtEveryBoundary() {
        for (level in 1..MAX_LEVEL) {
            assertEquals(level, levelForSeconds(secondsForLevel(level)))
            assertEquals(level, levelForDays(daysForLevel(level)))
            if (level > 1) {
                assertEquals(level - 1, levelForSeconds(secondsForLevel(level) - 1))
                assertEquals(level - 1, levelForDays(daysForLevel(level) - 1))
            }
        }
        assertEquals(MAX_LEVEL, levelForSeconds(Long.MAX_VALUE / 2))
    }

    @Test
    fun shortDailyActivityLevelsByDays() {
        // A year of daily one-minute holds: time alone only reaches level 3.
        val l = activityLevel(totalSeconds = 365 * 60, practiceDays = 365)
        assertEquals(3, levelForSeconds(365 * 60))
        assertEquals(32, l.level)
        assertTrue(l.next is NextLevel.Days)
    }

    @Test
    fun longActivityLevelsByHours() {
        val l = activityLevel(totalSeconds = 100 * 3600, practiceDays = 10)
        assertEquals(levelForSeconds(100 * 3600), l.level)
        assertTrue(l.next is NextLevel.Time)
    }

    @Test
    fun nextLevelUsesTheCloserMeasure() {
        // Level 3 by both. Days are halfway to level 4 (3 of 2..4), time has barely started.
        val l = activityLevel(totalSeconds = secondsForLevel(3) + 10, practiceDays = 3)
        assertEquals(3, l.level)
        assertEquals(NextLevel.Days(daysForLevel(4) - 3), l.next)
        assertEquals(0.5f, l.progress)
    }

    @Test
    fun maxLevelHasNoNext() {
        val l = activityLevel(totalSeconds = 10_000L * 3600, practiceDays = 0)
        assertEquals(MAX_LEVEL, l.level)
        assertNull(l.next)
        assertEquals(1f, l.progress)
    }

    @Test
    fun practiceDaysCountDistinctDays() {
        val d = LocalDate.of(2026, 11, 16)
        val levels = activityLevels(listOf(DayEntry(1, d, 60), DayEntry(1, d, 60), DayEntry(1, d.plusDays(1), 5)))
        assertEquals(activityLevel(125, 2), levels[1])
    }
}
