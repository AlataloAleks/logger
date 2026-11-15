package dev.tohma.logger.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class TimeRulesTest {
    private val helsinki = "Europe/Helsinki"

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: String = helsinki) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test
    fun beforeDayStartCountsAsPreviousDay() {
        assertEquals(LocalDate.of(2026, 11, 9), logicalDate(at(2026, 11, 10, 2, 30), helsinki, 4))
    }

    @Test
    fun atDayStartCountsAsNewDay() {
        assertEquals(LocalDate.of(2026, 11, 10), logicalDate(at(2026, 11, 10, 4, 0), helsinki, 4))
    }

    @Test
    fun midnightDayStartIsCalendarDay() {
        assertEquals(LocalDate.of(2026, 11, 10), logicalDate(at(2026, 11, 10, 0, 0), helsinki, 0))
    }

    @Test
    fun usesTheSessionsOwnZone() {
        // 23:30 in New York is already the next morning in Helsinki.
        val t = at(2026, 11, 10, 23, 30, "America/New_York")
        assertEquals(LocalDate.of(2026, 11, 10), logicalDate(t, "America/New_York", 4))
        assertEquals(LocalDate.of(2026, 11, 11), logicalDate(t, helsinki, 4))
    }

    @Test
    fun formatsClock() {
        assertEquals("0:00", formatClock(0))
        assertEquals("1:20", formatClock(80))
        assertEquals("1:05:07", formatClock(3907))
        assertEquals("0:00", formatClock(-5))
    }

    @Test
    fun formatsDuration() {
        assertEquals("45s", formatDuration(45))
        assertEquals("12m", formatDuration(12 * 60 + 30))
        assertEquals("1h 05m", formatDuration(3900))
    }

    @Test
    fun pomodoroPhasePlan() {
        val s = dev.tohma.logger.data.db.SettingsEntity() // 25 / 5 / 15 every 4
        assertEquals(listOf(1500L, 300L, 1500L, 300L, 1500L, 300L, 1500L, 900L, 1500L), (0..8).map { s.pomodoroPhaseSeconds(it) })
        assertEquals("Break · round 4", phaseLabel(7))
    }
}
