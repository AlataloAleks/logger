package com.aleksalatalo.logger.domain

import com.aleksalatalo.logger.data.db.SessionEntity
import com.aleksalatalo.logger.data.db.SessionMode
import com.aleksalatalo.logger.data.db.SettingsEntity
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

    @Test
    fun balanceSplitsFocusAndLeisure() {
        val entries = listOf(
            e(today, 600, activity = 1),
            e(today, 300, activity = 2),
            e(today.minusWeeks(1), 100, activity = 2),
        )
        val b = computeBalance(entries, leisureIds = setOf(2), today = today, weekStart = DayOfWeek.MONDAY)
        assertEquals(600L, b.focusThisWeek)
        assertEquals(300L, b.leisureThisWeek)
        assertEquals(100L, b.leisureLastWeek)
        assertEquals(600L, b.weeks.last().seconds)
        assertEquals(listOf(100L, 300L), b.leisureWeeks.takeLast(2))
    }

    @Test
    fun periodRangesAndTotals() {
        val (week, previousWeek) = periodRanges(StatsPeriod.WEEK, today, DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 11, 16)..LocalDate.of(2026, 11, 22), week)
        assertEquals(LocalDate.of(2026, 11, 9)..LocalDate.of(2026, 11, 15), previousWeek)
        val (month, previousMonth) = periodRanges(StatsPeriod.MONTH, today, DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 11, 1)..LocalDate.of(2026, 11, 30), month)
        assertEquals(LocalDate.of(2026, 10, 1)..LocalDate.of(2026, 10, 31), previousMonth)
        val entries = listOf(e(today, 60, 1), e(LocalDate.of(2026, 11, 10), 30, 1), e(today, 5, 2))
        assertEquals(mapOf(1L to 60L, 2L to 5L), totalsIn(entries, week))
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L, 60L), recentDays(entries, setOf(1), today))
    }
}
