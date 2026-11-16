package com.aleksalatalo.logger.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aleksalatalo.logger.domain.formatDuration
import com.aleksalatalo.logger.domain.heatLevels
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Alpha of the series color for heatmap levels 1..4 (a single-hue sequential scale). */
private val HEAT_ALPHA = listOf(0f, 0.3f, 0.5f, 0.75f, 1f)
private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

/** Selected value shown above a chart, in text colors (never the series color). */
@Composable
private fun Readout(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * GitHub-style contribution grid: one column per week, one row per weekday, from [start]
 * (a week start) to [today]. Tap a day to see its total. Scrolled to the latest week.
 */
@Composable
fun Heatmap(start: LocalDate, today: LocalDate, daily: Map<LocalDate, Long>, color: Color) {
    val weeks = (ChronoUnit.DAYS.between(start, today) / 7 + 1).toInt()
    val level = remember(daily) { heatLevels(daily.values) }
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val cell = 13.dp
    val gap = 2.dp
    val labelHeight = 16.dp
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }

    Column {
        Readout(
            selected?.let { "${it.format(dayFormat)}: ${formatDuration(daily[it] ?: 0)}" }
                ?: "Tap a day to see its total",
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.horizontalScroll(scroll)) {
            val density = LocalDensity.current
            val width = (cell + gap) * weeks
            Canvas(
                Modifier
                    .size(width, labelHeight + (cell + gap) * 7)
                    .pointerInput(start, weeks) {
                        detectTapGestures { pos ->
                            val step = with(density) { (cell + gap).toPx() }
                            val top = with(density) { labelHeight.toPx() }
                            val col = (pos.x / step).toInt()
                            val row = ((pos.y - top) / step).toInt()
                            if (pos.y >= top && col in 0 until weeks && row in 0..6) {
                                val date = start.plusDays(col * 7L + row)
                                selected = if (date.isAfter(today)) null else date
                            }
                        }
                    },
            ) {
                val step = (cell + gap).toPx()
                val size = cell.toPx()
                val top = labelHeight.toPx()
                var lastMonth = -1
                for (col in 0 until weeks) {
                    val weekStart = start.plusDays(col * 7L)
                    // Month label above the first week that starts in a new month.
                    if (weekStart.monthValue != lastMonth) {
                        if (lastMonth != -1 || weekStart.dayOfMonth <= 7) {
                            drawText(
                                measurer,
                                weekStart.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                                topLeft = Offset(col * step, 0f),
                                style = labelStyle.copy(color = labelColor),
                            )
                        }
                        lastMonth = weekStart.monthValue
                    }
                    for (row in 0..6) {
                        val date = weekStart.plusDays(row.toLong())
                        if (date.isAfter(today)) continue
                        val l = level(daily[date] ?: 0)
                        drawRoundRect(
                            color = if (l == 0) empty else color.copy(alpha = HEAT_ALPHA[l]),
                            topLeft = Offset(col * step, top + row * step),
                            size = Size(size, size),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )
                        if (date == selected) {
                            drawRoundRect(
                                color = labelColor,
                                topLeft = Offset(col * step, top + row * step),
                                size = Size(size, size),
                                cornerRadius = CornerRadius(2.dp.toPx()),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Less", style = labelStyle, color = labelColor)
            Spacer(Modifier.width(2.dp))
            (0..4).forEach { l ->
                Box(
                    Modifier.size(10.dp).background(
                        if (l == 0) empty else color.copy(alpha = HEAT_ALPHA[l]),
                        RoundedCornerShape(2.dp),
                    ),
                )
            }
            Spacer(Modifier.width(2.dp))
            Text("More", style = labelStyle, color = labelColor)
        }
    }
}

/** Bar with a 4dp rounded data end, anchored square to the baseline. */
private fun DrawScope.bar(left: Float, width: Float, baseline: Float, height: Float, color: Color) {
    if (height <= 0f) return
    val r = minOf(4.dp.toPx(), width / 2, height)
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                left = left, top = baseline - height, right = left + width, bottom = baseline,
                topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
            ),
        )
    }
    drawPath(path, color)
}

/**
 * Vertical bars with optional comparison bars drawn beside them in a lighter step of the same
 * hue. Tap a bar to read its value; labels are thinned to every [labelEvery]-th slot.
 */
@Composable
fun BarChart(
    labels: List<String>,
    values: List<Long>,
    color: Color,
    compare: List<Long>? = null,
    compareName: String = "",
    valueName: String = "",
    labelEvery: Int = 1,
    readoutLabels: List<String> = labels,
) {
    val max = maxOf(values.maxOrNull() ?: 0, compare?.maxOrNull() ?: 0).coerceAtLeast(1)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val compareColor = color.copy(alpha = 0.35f)
    var selected by remember(labels) { mutableStateOf<Int?>(null) }

    Column {
        Readout(
            selected?.let { i ->
                buildString {
                    append(readoutLabels[i]).append(": ").append(formatDuration(values[i]))
                    if (compare != null) append("  ·  $compareName ${formatDuration(compare[i])}")
                }
            } ?: "Tap a bar to see its value",
        )
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .pointerInput(labels) {
                    detectTapGestures { pos ->
                        val i = (pos.x / (size.width.toFloat() / labels.size)).toInt()
                        selected = i.coerceIn(labels.indices).takeIf { it != selected }
                    }
                },
        ) {
            val labelSpace = 18.dp.toPx()
            val baseline = size.height - labelSpace
            val slot = size.width / labels.size
            val gap = 2.dp.toPx()
            val groupWidth = slot * 0.6f
            val barWidth = if (compare != null) (groupWidth - gap) / 2 else groupWidth
            labels.indices.forEach { i ->
                val left = i * slot + (slot - groupWidth) / 2
                val selectedAlpha = if (selected == null || selected == i) 1f else 0.45f
                if (compare != null) {
                    bar(left, barWidth, baseline, compare[i] * baseline / max, compareColor.copy(alpha = compareColor.alpha * selectedAlpha))
                }
                val x = if (compare != null) left + barWidth + gap else left
                bar(x, barWidth, baseline, values[i] * baseline / max, color.copy(alpha = selectedAlpha))
                if (i % labelEvery == 0 || i == labels.lastIndex) {
                    val layout = measurer.measure(labels[i], labelStyle.copy(color = labelColor))
                    drawText(layout, topLeft = Offset(i * slot + (slot - layout.size.width) / 2, baseline + 4.dp.toPx()))
                }
            }
            drawLine(baselineColor, Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
        }
        if (compare != null) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem(color, valueName)
                LegendItem(compareColor, compareName)
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One labelled horizontal bar per item; identity comes from the name, not only the color. */
@Composable
fun HorizontalBars(items: List<Triple<String, Color, Long>>) {
    val max = items.maxOfOrNull { it.third }?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { (name, color, seconds) ->
            Column {
                Row {
                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatDuration(seconds), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth((seconds.toFloat() / max).coerceAtLeast(0.01f))
                        .height(8.dp)
                        .background(color, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)),
                )
            }
        }
    }
}
