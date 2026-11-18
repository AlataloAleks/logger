package com.aleksalatalo.logger.domain

import com.aleksalatalo.logger.data.db.SessionEntity
import com.aleksalatalo.logger.data.db.SettingsEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Tracked seconds of one finished session, assigned to its logical day. */
data class DayEntry(val activityId: Long, val day: LocalDate, val seconds: Long)

data class Bar(val label: String, val seconds: Long)

data class ActivityTotal(val activityId: Long, val seconds: Long)

data class Stats(
    val totalSeconds: Long,
    /** Seconds per day for the heatmap, from [heatmapStart] (a week start) to today. */
    val heatmapStart: LocalDate,
    val today: LocalDate,
    val daily: Map<LocalDate, Long>,
    /** Seven days each, starting on the configured week start. */
    val thisWeek: List<Long>,
    val lastWeek: List<Long>,
    /** Average weekly total of the four weeks before this one. */
    val fourWeekAverage: Long,
    val weeks: List<Bar>,
    val months: List<Bar>,
    val perActivity: List<ActivityTotal>,
    val currentStreak: Int,
    val longestStreak: Int,
    val activeDaysLast30: Int,
) {
    val thisWeekTotal get() = thisWeek.sum()
    val lastWeekTotal get() = lastWeek.sum()
}

/** Finished sessions only; running sessions are not counted until they stop. */
fun dayEntries(sessions: List<SessionEntity>, settings: SettingsEntity): List<DayEntry> =
    sessions.filter { it.endedAt != null }.map {
        DayEntry(
            activityId = it.activityId,
            day = logicalDate(it.startedAt, it.zoneId, settings.dayStartHour),
            seconds = it.workSeconds + if (settings.countBreaks) it.breakSeconds else 0,
        )
    }

fun weekStartOf(date: LocalDate, weekStart: DayOfWeek): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(weekStart))

/** All statistics for [entries], which may be pre-filtered to one activity. */
fun computeStats(entries: List<DayEntry>, today: LocalDate, weekStart: DayOfWeek): Stats {
    val daily = entries.groupBy { it.day }.mapValues { (_, list) -> list.sumOf { it.seconds } }
    fun day(d: LocalDate) = daily[d] ?: 0L
    fun range(from: LocalDate, days: Long) = (0 until days).sumOf { day(from.plusDays(it)) }

    val thisWeekStart = weekStartOf(today, weekStart)
    val lastWeekStart = thisWeekStart.minusWeeks(1)

    val weeks = (11 downTo 0).map { back ->
        val start = thisWeekStart.minusWeeks(back.toLong())
        Bar("${start.dayOfMonth}.${start.monthValue}.", range(start, 7))
    }
    val thisMonth = YearMonth.from(today)
    val months = (11 downTo 0).map { back ->
        val month = thisMonth.minusMonths(back.toLong())
        Bar(
            month.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()),
            range(month.atDay(1), month.lengthOfMonth().toLong()),
        )
    }

    // Streaks count consecutive days with any tracked time. Today does not break the
    // current streak while it is still empty.
    var current = 0
    var d = if (day(today) > 0) today else today.minusDays(1)
    while (day(d) > 0) { current++; d = d.minusDays(1) }
    var longest = 0
    var run = 0
    var previous: LocalDate? = null
    daily.keys.filter { day(it) > 0 }.sorted().forEach { date ->
        run = if (previous != null && ChronoUnit.DAYS.between(previous, date) == 1L) run + 1 else 1
        longest = maxOf(longest, run)
        previous = date
    }

    return Stats(
        totalSeconds = entries.sumOf { it.seconds },
        heatmapStart = thisWeekStart.minusWeeks(52),
        today = today,
        daily = daily,
        thisWeek = (0L until 7).map { day(thisWeekStart.plusDays(it)) },
        lastWeek = (0L until 7).map { day(lastWeekStart.plusDays(it)) },
        fourWeekAverage = (1L..4).sumOf { range(thisWeekStart.minusWeeks(it), 7) } / 4,
        weeks = weeks,
        months = months,
        perActivity = entries.groupBy { it.activityId }
            .map { (id, list) -> ActivityTotal(id, list.sumOf { it.seconds }) }
            .sortedByDescending { it.seconds },
        currentStreak = current,
        longestStreak = longest,
        activeDaysLast30 = (0L until 30).count { day(today.minusDays(it)) > 0 },
    )
}

/**
 * Heatmap intensity 0..4: 0 = no time, 1..4 = quartiles of the non-empty days, so a few very
 * long days do not wash out everything else.
 */
fun heatLevels(values: Collection<Long>): (Long) -> Int {
    val sorted = values.filter { it > 0 }.sorted()
    if (sorted.isEmpty()) return { 0 }
    val cuts = listOf(0.25, 0.5, 0.75).map { sorted[((sorted.size - 1) * it).toInt()] }
    return { v ->
        when {
            v <= 0 -> 0
            v <= cuts[0] -> 1
            v <= cuts[1] -> 2
            v <= cuts[2] -> 3
            else -> 4
        }
    }
}

/** Focus vs Leisure time. Weeks start on the configured week start. */
data class Balance(
    val focusThisWeek: Long,
    val leisureThisWeek: Long,
    val focusLastWeek: Long,
    val leisureLastWeek: Long,
    /** Last 12 weeks, oldest first; labels are each week's start as "d.M.". */
    val weeks: List<Bar>,
    val leisureWeeks: List<Long>,
)

fun computeBalance(
    entries: List<DayEntry>,
    leisureIds: Set<Long>,
    today: LocalDate,
    weekStart: DayOfWeek,
): Balance {
    val thisWeekStart = weekStartOf(today, weekStart)
    fun sum(from: LocalDate, leisure: Boolean) = entries
        .filter { (it.activityId in leisureIds) == leisure && !it.day.isBefore(from) && it.day.isBefore(from.plusWeeks(1)) }
        .sumOf { it.seconds }
    val starts = (11 downTo 0).map { thisWeekStart.minusWeeks(it.toLong()) }
    return Balance(
        focusThisWeek = sum(thisWeekStart, false),
        leisureThisWeek = sum(thisWeekStart, true),
        focusLastWeek = sum(thisWeekStart.minusWeeks(1), false),
        leisureLastWeek = sum(thisWeekStart.minusWeeks(1), true),
        weeks = starts.map { Bar("${it.dayOfMonth}.${it.monthValue}.", sum(it, false)) },
        leisureWeeks = starts.map { sum(it, true) },
    )
}

/** Period chosen on the statistics overview. */
enum class StatsPeriod(val label: String) { WEEK("Week"), MONTH("Month"), YEAR("Year"), ALL("All") }

/** Inclusive date range of [period] containing [today], and the same-length range before it (null for ALL). */
fun periodRanges(period: StatsPeriod, today: LocalDate, weekStart: DayOfWeek): Pair<ClosedRange<LocalDate>, ClosedRange<LocalDate>?> =
    when (period) {
        StatsPeriod.WEEK -> {
            val start = weekStartOf(today, weekStart)
            (start..start.plusDays(6)) to (start.minusWeeks(1)..start.minusDays(1))
        }
        StatsPeriod.MONTH -> {
            val start = today.withDayOfMonth(1)
            val previous = start.minusMonths(1)
            (start..start.plusMonths(1).minusDays(1)) to (previous..start.minusDays(1))
        }
        StatsPeriod.YEAR -> {
            val start = today.withDayOfYear(1)
            (start..start.plusYears(1).minusDays(1)) to (start.minusYears(1)..start.minusDays(1))
        }
        StatsPeriod.ALL -> (LocalDate.MIN..LocalDate.MAX) to null
    }

/** Seconds per activity within [range]. */
fun totalsIn(entries: List<DayEntry>, range: ClosedRange<LocalDate>): Map<Long, Long> =
    entries.filter { it.day in range }.groupBy { it.activityId }.mapValues { (_, l) -> l.sumOf { it.seconds } }

/** Seconds per day for the last [days] days ending [today], oldest first, for the given activities. */
fun recentDays(entries: List<DayEntry>, activityIds: Set<Long>, today: LocalDate, days: Int = 7): List<Long> {
    val byDay = entries.filter { it.activityId in activityIds }.groupBy { it.day }.mapValues { (_, l) -> l.sumOf { it.seconds } }
    return (days - 1 downTo 0).map { byDay[today.minusDays(it.toLong())] ?: 0L }
}
