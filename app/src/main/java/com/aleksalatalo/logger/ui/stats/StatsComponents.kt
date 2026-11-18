package com.aleksalatalo.logger.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aleksalatalo.logger.domain.Stats
import com.aleksalatalo.logger.domain.StatsPeriod
import com.aleksalatalo.logger.domain.formatDuration
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Neutral series color for Leisure time, so Focus stays the colored, primary series. */
@Composable
fun leisureColor(): Color = MaterialTheme.colorScheme.outline

/** "+12%", "-5%", "new", or null when there is nothing to compare. */
fun changeText(current: Long, previous: Long?): String? = when {
    previous == null -> null
    previous == 0L -> if (current > 0) "new" else null
    else -> {
        val pct = ((current - previous) * 100.0 / previous).roundToInt()
        "${if (pct >= 0) "+" else ""}$pct%"
    }
}

fun periodTitle(period: StatsPeriod): String = when (period) {
    StatsPeriod.WEEK -> "This week"
    StatsPeriod.MONTH -> "This month"
    StatsPeriod.YEAR -> "This year"
    StatsPeriod.ALL -> "All time"
}

private fun previousName(period: StatsPeriod): String = when (period) {
    StatsPeriod.WEEK -> "last week"
    StatsPeriod.MONTH -> "last month"
    StatsPeriod.YEAR -> "last year"
    StatsPeriod.ALL -> ""
}

/** Detail line of a total tile: change vs the previous period. */
fun changeDetail(period: StatsPeriod, current: Long, previous: Long?): String {
    return when (previous) {
        null -> "Since the start"
        0L -> "None ${previousName(period)}"
        else -> "${changeText(current, previous)} vs ${previousName(period)}"
    }
}

@Composable
fun PeriodSelector(selected: StatsPeriod, onSelect: (StatsPeriod) -> Unit) {
    val periods = StatsPeriod.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        periods.forEachIndexed { i, p ->
            SegmentedButton(
                selected = p == selected,
                onClick = { onSelect(p) },
                shape = SegmentedButtonDefaults.itemShape(i, periods.size),
                label = { Text(p.label) },
            )
        }
    }
}

@Composable
fun Tile(title: String, value: String, detail: String, modifier: Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

/** Small neutral label such as "Leisure" or "Archived". */
@Composable
fun Tag(text: String) {
    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

fun streakText(days: Int) = "$days ${if (days == 1) "day" else "days"}"

@Composable
fun EmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** This week vs last week, by weekday. */
@Composable
fun WeekCompareSection(stats: Stats, weekStart: DayOfWeek, color: Color) {
    Section("This week vs last week") {
        BarChart(
            labels = (0L until 7).map { weekStart.plus(it).getDisplayName(TextStyle.NARROW, Locale.getDefault()) },
            readoutLabels = (0L until 7).map { weekStart.plus(it).getDisplayName(TextStyle.FULL, Locale.getDefault()) },
            values = stats.thisWeek,
            compare = stats.lastWeek,
            valueName = "This week",
            compareName = "Last week",
            color = color,
        )
    }
}

@Composable
fun HeatmapSection(title: String, stats: Stats, color: Color) {
    Section(title) {
        Heatmap(stats.heatmapStart, stats.today, stats.daily, color)
    }
}

/** 12-week / 12-month trend with a toggle and the 4-week average. */
@Composable
fun TrendSection(title: String, stats: Stats, color: Color) {
    var months by rememberSaveable { mutableStateOf(false) }
    Section(title) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !months, onClick = { months = false }, label = { Text("12 weeks") })
            FilterChip(selected = months, onClick = { months = true }, label = { Text("12 months") })
        }
        val bars = if (months) stats.months else stats.weeks
        BarChart(
            labels = bars.map { it.label },
            readoutLabels = bars.map { if (months) it.label else "Week of ${it.label}" },
            values = bars.map { it.seconds },
            color = color,
            labelEvery = if (months) 1 else 3,
        )
        Text(
            "4-week average ${formatDuration(stats.fourWeekAverage)} per week",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
