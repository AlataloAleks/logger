package dev.tohma.logger.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tohma.logger.data.db.SessionRow
import dev.tohma.logger.domain.formatClock
import dev.tohma.logger.domain.RunningSession
import dev.tohma.logger.domain.phaseLabel
import kotlinx.coroutines.delay

@Composable
fun ColorDot(color: Int, size: Int = 14) {
    Box(Modifier.size(size.dp).background(Color(color), CircleShape))
}

@Composable
fun SectionHeader(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Current wall-clock time, refreshed every second, aligned to second boundaries. */
@Composable
fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(1000 - value % 1000)
    }
}

/** Elapsed seconds of a running session; always derived from its start timestamp. */
@Composable
fun elapsedSeconds(startedAt: Long): Long {
    val now = rememberNow().value
    return ((now - startedAt) / 1000).coerceAtLeast(0)
}

/** Seconds left of a timer or Pomodoro phase (never negative), or seconds elapsed for a stopwatch. */
@Composable
fun displaySeconds(running: RunningSession): Long {
    val now = rememberNow().value
    val end = running.endsAt
    return if (end != null) {
        ((end - now + 999) / 1000).coerceAtLeast(0)
    } else {
        ((now - running.row.session.startedAt) / 1000).coerceAtLeast(0)
    }
}

/** Short mode description: "Timer · 2:00", "Break · round 2", or null for a stopwatch. */
fun modeLabel(running: RunningSession): String? = when {
    running.phase != null -> phaseLabel(running.phase.phaseIndex)
    running.row.session.plannedSeconds != null -> "Timer · ${formatClock(running.row.session.plannedSeconds)}"
    else -> null
}

@Composable
fun RunningBanner(running: RunningSession, onClick: () -> Unit, onStop: () -> Unit) {
    val row = running.row
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorDot(row.activityColor)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.activityName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                listOfNotNull(modeLabel(running), row.session.description).joinToString(" · ").ifEmpty { null }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                formatClock(displaySeconds(running)),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = onStop) { Text("Stop") }
        }
    }
}
