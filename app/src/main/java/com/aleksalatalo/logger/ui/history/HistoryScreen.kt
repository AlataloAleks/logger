package com.aleksalatalo.logger.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aleksalatalo.logger.LoggerApp
import com.aleksalatalo.logger.data.db.SessionRow
import com.aleksalatalo.logger.data.db.SettingsEntity
import com.aleksalatalo.logger.domain.SessionEngine
import com.aleksalatalo.logger.domain.formatClock
import com.aleksalatalo.logger.domain.formatDuration
import com.aleksalatalo.logger.domain.logicalDate
import com.aleksalatalo.logger.ui.common.ColorDot
import com.aleksalatalo.logger.ui.common.SectionHeader
import com.aleksalatalo.logger.ui.common.elapsedSeconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class HistoryDay(val date: LocalDate, val totalSeconds: Long, val sessions: List<SessionRow>)

data class HistoryState(
    val loading: Boolean = true,
    val days: List<HistoryDay> = emptyList(),
    val dayStartHour: Int = SettingsEntity().dayStartHour,
)

class HistoryViewModel(engine: SessionEngine, settings: Flow<SettingsEntity>) : ViewModel() {
    val state: StateFlow<HistoryState> = combine(engine.history, settings) { rows, s ->
        val days = rows
            .groupBy { logicalDate(it.session.startedAt, it.session.zoneId, s.dayStartHour) }
            .map { (date, sessions) ->
                val total = sessions.filter { it.session.endedAt != null }.sumOf {
                    it.session.workSeconds + if (s.countBreaks) it.session.breakSeconds else 0
                }
                HistoryDay(date, total, sessions)
            }
            .sortedByDescending { it.date }
        HistoryState(loading = false, days = days, dayStartHour = s.dayStartHour)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryState())

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = (this[APPLICATION_KEY] as LoggerApp).container
                HistoryViewModel(c.sessionEngine, c.settings)
            }
        }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM")
private val dayYearFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy")

private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(if (date.year == today.year) dayFormat else dayYearFormat)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenSession: (SessionRow) -> Unit,
    onAddManual: () -> Unit,
    viewModel: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val today = logicalDate(System.currentTimeMillis(), ZoneId.systemDefault().id, state.dayStartHour)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddManual) {
                Icon(Icons.Default.Add, contentDescription = "Add session manually")
            }
        },
    ) { padding ->
        if (!state.loading && state.days.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No sessions yet.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
        ) {
            state.days.forEach { day ->
                item(key = "day-${day.date}") {
                    SectionHeader(dayLabel(day.date, today), trailing = formatDuration(day.totalSeconds))
                }
                items(day.sessions, key = { it.session.id }) { row ->
                    SessionListItem(row, onClick = { onOpenSession(row) })
                }
            }
        }
    }
}

@Composable
private fun SessionListItem(row: SessionRow, onClick: () -> Unit) {
    val s = row.session
    val start = Instant.ofEpochMilli(s.startedAt).atZone(ZoneId.of(s.zoneId)).format(timeFormat)
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { ColorDot(row.activityColor) },
        headlineContent = { Text(row.activityName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = s.description?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                if (s.endedAt == null) {
                    Text(formatClock(elapsedSeconds(s.startedAt)), color = MaterialTheme.colorScheme.primary)
                    Text("Running", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text(formatDuration(s.workSeconds))
                    Text(start, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}
