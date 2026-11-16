package com.aleksalatalo.logger.domain

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Activity levels 1..100. An activity levels up by tracked time (level 100 at 10,000 hours) or by
 * practice days, i.e. days with any tracked time (level 100 at 3,650 days, ten years of daily
 * practice), whichever gives the higher level. Short daily activities therefore progress through
 * consistency, long ones through hours. Both use the same squared curve, so early levels come fast.
 */
const val MAX_LEVEL = 100
private const val MAX_SECONDS = 10_000L * 3600
private const val MAX_DAYS = 3650.0

private fun curve(level: Int): Double {
    val x = (level.coerceIn(1, MAX_LEVEL) - 1) / (MAX_LEVEL - 1).toDouble()
    return x * x
}

/** Tracked seconds needed to reach [level]. */
fun secondsForLevel(level: Int): Long = ceil(MAX_SECONDS * curve(level)).toLong()

/** Practice days needed to reach [level]. */
fun daysForLevel(level: Int): Int = ceil(MAX_DAYS * curve(level)).toInt()

/** Highest level whose threshold [value] has reached, given a threshold function. */
private fun levelFor(value: Double, max: Double, threshold: (Int) -> Double): Int {
    if (value <= 0) return 1
    // Invert the curve, then correct for rounding at the boundary.
    var level = (1 + floor((MAX_LEVEL - 1) * sqrt(value / max))).toInt().coerceIn(1, MAX_LEVEL)
    while (level < MAX_LEVEL && threshold(level + 1) <= value) level++
    while (level > 1 && threshold(level) > value) level--
    return level
}

fun levelForSeconds(seconds: Long): Int =
    levelFor(seconds.toDouble(), MAX_SECONDS.toDouble()) { secondsForLevel(it).toDouble() }

fun levelForDays(days: Int): Int = levelFor(days.toDouble(), MAX_DAYS) { daysForLevel(it).toDouble() }

/** What is still needed for the next level, in the measure that is closer to it. */
sealed interface NextLevel {
    data class Time(val seconds: Long) : NextLevel
    data class Days(val days: Int) : NextLevel
}

data class ActivityLevel(
    val level: Int,
    /** Progress from this level to the next, 0..1, in the measure that is closer. 1 at max level. */
    val progress: Float,
    /** null at [MAX_LEVEL]. */
    val next: NextLevel?,
)

fun activityLevel(totalSeconds: Long, practiceDays: Int): ActivityLevel {
    val level = maxOf(levelForSeconds(totalSeconds), levelForDays(practiceDays))
    if (level == MAX_LEVEL) return ActivityLevel(level, 1f, null)

    fun fraction(value: Double, from: Double, to: Double) = ((value - from) / (to - from)).coerceIn(0.0, 1.0)
    val byTime = fraction(totalSeconds.toDouble(), secondsForLevel(level).toDouble(), secondsForLevel(level + 1).toDouble())
    val byDays = fraction(practiceDays.toDouble(), daysForLevel(level).toDouble(), daysForLevel(level + 1).toDouble())
    return if (byTime >= byDays) {
        ActivityLevel(level, byTime.toFloat(), NextLevel.Time(secondsForLevel(level + 1) - totalSeconds))
    } else {
        ActivityLevel(level, byDays.toFloat(), NextLevel.Days(daysForLevel(level + 1) - practiceDays))
    }
}

/** Level of every activity with tracked time, from finished sessions' day entries. */
fun activityLevels(entries: List<DayEntry>): Map<Long, ActivityLevel> =
    entries.groupBy { it.activityId }.mapValues { (_, list) ->
        activityLevel(
            totalSeconds = list.sumOf { it.seconds },
            practiceDays = list.filter { it.seconds > 0 }.map { it.day }.toSet().size,
        )
    }
