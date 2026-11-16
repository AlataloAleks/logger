package dev.tohma.logger.domain

import dev.tohma.logger.data.db.SessionEntity
import dev.tohma.logger.data.db.SessionMode
import dev.tohma.logger.data.db.SettingsEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class StatsTest {
    // Wednesday.
    private val today = LocalDate.of(2026, 11, 18)

    private fun e(day: LocalDate, seconds: Long, activity: Long = 1) = DayEntry(activity, day, seconds)

    @Test
    fun weekComparisonUsesConfiguredWeekStart() {
        val entries = listOf(
            e(LocalDate.of(2026, 11, 16), 600), // Monday this week
            e(LocalDate.of(2026, 11, 18), 300), // today
            e(LocalDate.of(2026, 11, 15), 900), // Sunday last week
        )
        val monday = computeStats(entries, today, DayOfWeek.MONDAY)
        assertEquals(listOf(600L, 0, 300, 0, 0, 0, 0), monday.thisWeek)
        assertEquals(900L, monday.lastWeekTotal)

        val sunday = computeStats(entries, today, DayOfWeek.SUNDAY)
        assertEquals(listOf(900L, 600, 0, 300, 0, 0, 0), sunday.thisWeek)
        assertEquals(0L, sunday.lastWeekTotal)
    }

    @Test
    fun streaksIgnoreEmptyToday() {
        val entries = (1L..3).map { e(today.minusDays(it), 60) } + e(today.minusDays(10), 60)
        val stats = computeStats(entries, today, DayOfWeek.MONDAY)
        assertEquals(3, stats.currentStreak)
        assertEquals(3, stats.longestStreak)
        assertEquals(4, stats.activeDaysLast30)
    }

    @Test
    fun totalsPerActivityAreSorted() {
        val entries = listOf(e(today, 100, 1), e(today, 500, 2), e(today.minusDays(40), 50, 1))
        val stats = computeStats(entries, today, DayOfWeek.MONDAY)
        assertEquals(listOf(ActivityTotal(2, 500), ActivityTotal(1, 150)), stats.perActivity)
        assertEquals(650L, stats.totalSeconds)
    }

    @Test
    fun trendsCoverTwelvePeriodsEndingNow() {
        val stats = computeStats(listOf(e(today, 60), e(LocalDate.of(2026, 1, 5), 120)), today, DayOfWeek.MONDAY)
        assertEquals(12, stats.weeks.size)
        assertEquals(60L, stats.weeks.last().seconds)
        assertEquals(12, stats.months.size)
        assertEquals(60L, stats.months.last().seconds)
        assertEquals(120L, stats.months.first { it.seconds == 120L }.seconds) // January is within 12 months
    }

    @Test
    fun breaksCountOnlyWhenEnabledAndRunningSessionsAreSkipped() {
        val zone = "Europe/Helsinki"
        val start = ZonedDateTime.of(2026, 11, 18, 10, 0, 0, 0, ZoneId.of(zone)).toInstant().toEpochMilli()
        fun s(endedAt: Long?) = SessionEntity(
            activityId = 1, mode = SessionMode.POMODORO, startedAt = start, endedAt = endedAt, zoneId = zone,
            workSeconds = 1500, breakSeconds = 300, createdAt = 0, updatedAt = 0,
        )
        val sessions = listOf(s(start + 1_800_000), s(null))
        assertEquals(listOf(1500L), dayEntries(sessions, SettingsEntity()).map { it.seconds })
        assertEquals(listOf(1800L), dayEntries(sessions, SettingsEntity(countBreaks = true)).map { it.seconds })
    }

    @Test
    fun heatLevelsUseQuartiles() {
        val level = heatLevels(listOf(0L, 10, 20, 30, 40, 1000))
        assertEquals(listOf(0, 1, 1, 2, 3, 4), listOf(0L, 10, 20, 30, 40, 1000).map(level))
    }
}
