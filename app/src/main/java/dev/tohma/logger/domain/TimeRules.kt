package dev.tohma.logger.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The calendar day a moment belongs to when the user's day starts at [dayStartHour]
 * instead of midnight. With dayStartHour = 4, 02:30 on Tuesday still counts as Monday.
 */
fun logicalDate(epochMillis: Long, zoneId: String, dayStartHour: Int): LocalDate =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.of(zoneId))
        .minusHours(dayStartHour.toLong())
        .toLocalDate()

/** Clock style: 5:07, 1:05:07. Used for live timers. */
fun formatClock(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Compact style: 45s, 12m, 1h 05m. Used in lists and totals. */
fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> "%dh %02dm".format(h, m)
        m > 0 -> "${m}m"
        else -> "${s}s"
    }
}
