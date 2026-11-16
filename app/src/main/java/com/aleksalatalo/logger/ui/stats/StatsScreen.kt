package com.aleksalatalo.logger.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aleksalatalo.logger.LoggerApp
import com.aleksalatalo.logger.data.db.ActivityEntity
import com.aleksalatalo.logger.data.db.ActivityGroupEntity
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.List
import com.aleksalatalo.logger.domain.SessionEngine
import com.aleksalatalo.logger.domain.Stats
import com.aleksalatalo.logger.domain.computeStats
import com.aleksalatalo.logger.domain.dayEntries
import com.aleksalatalo.logger.domain.formatDuration
import com.aleksalatalo.logger.domain.logicalDate
import com.aleksalatalo.logger.data.ActivityRepository
import com.aleksalatalo.logger.data.db.SettingsEntity
import com.aleksalatalo.logger.ui.common.ColorDot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

sealed interface StatsFilter {
    data object All : StatsFilter
    data class Group(val id: Long) : StatsFilter
    data class Activity(val id: Long) : StatsFilter
}

data class StatsUiState(
    val loading: Boolean = true,
    val stats: Stats? = null,
    /** Activities that can be filtered on: not archived, or with recorded time. */
    val activities: List<ActivityEntity> = emptyList(),
    /** Groups with at least one listed activity. */
    val groups: List<ActivityGroupEntity> = emptyList(),
    val filter: StatsFilter = StatsFilter.All,
    /** The selected activity, when filtering by one activity. */
    val selected: ActivityEntity? = null,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
)

class StatsViewModel(
    engine: SessionEngine,
    repository: ActivityRepository,
    settings: Flow<SettingsEntity>,
) : ViewModel() {
    private val filter = MutableStateFlow<StatsFilter>(StatsFilter.All)

    val state: StateFlow<StatsUiState> =
        combine(engine.history, repository.activities, repository.groups, settings, filter) { rows, activities, groups, s, f ->
            val entries = dayEntries(rows.map { it.session }, s)
            val withTime = entries.map { it.activityId }.toSet()
            val weekStart = DayOfWeek.of(s.weekStart)
            val today = logicalDate(System.currentTimeMillis(), ZoneId.systemDefault().id, s.dayStartHour)
            // A group includes its archived activities, so their history still counts.
            val included: Set<Long>? = when (f) {
                StatsFilter.All -> null
                is StatsFilter.Group -> activities.filter { it.groupId == f.id }.map { it.id }.toSet()
                is StatsFilter.Activity -> setOf(f.id)
            }
            val filtered = if (included == null) entries else entries.filter { it.activityId in included }
            val listed = activities.filter { !it.archived || it.id in withTime }
            StatsUiState(
                loading = false,
                stats = computeStats(filtered, today, weekStart),
                activities = listed,
                groups = groups.filter { g -> listed.any { it.groupId == g.id } },
                filter = f,
                selected = (f as? StatsFilter.Activity)?.let { a -> activities.firstOrNull { it.id == a.id } },
                weekStart = weekStart,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun select(f: StatsFilter) {
        filter.value = f
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = (this[APPLICATION_KEY] as LoggerApp).container
                StatsViewModel(c.sessionEngine, c.activityRepository, c.settings)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit, viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var months by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Statistics") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        val stats = state.stats ?: return@Scaffold
        val color = state.selected?.let { Color(it.color) } ?: MaterialTheme.colorScheme.primary
        val dayNames = (0L until 7).map {
            state.weekStart.plus(it).getDisplayName(TextStyle.NARROW, Locale.getDefault())
        }
        val fullDayNames = (0L until 7).map {
            state.weekStart.plus(it).getDisplayName(TextStyle.FULL, Locale.getDefault())
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = state.filter == StatsFilter.All,
                            onClick = { viewModel.select(StatsFilter.All) },
                            label = { Text("All") },
                        )
                    }
                    items(state.groups, key = { "g${it.id}" }) { g ->
                        FilterChip(
                            selected = state.filter == StatsFilter.Group(g.id),
                            onClick = { viewModel.select(StatsFilter.Group(g.id)) },
                            label = { Text(g.name) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Group", Modifier.size(16.dp)) },
                        )
                    }
                    items(state.activities, key = { "a${it.id}" }) { a ->
                        FilterChip(
                            selected = state.selected?.id == a.id,
                            onClick = { viewModel.select(StatsFilter.Activity(a.id)) },
                            label = { Text(a.name) },
                            leadingIcon = { ColorDot(a.color, size = 10) },
                        )
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("This week", formatDuration(stats.thisWeekTotal), weekDelta(stats), Modifier.weight(1f))
                    Tile(
                        "Streak",
                        "${stats.currentStreak} ${if (stats.currentStreak == 1) "day" else "days"}",
                        "Best ${stats.longestStreak}",
                        Modifier.weight(1f),
                    )
                    Tile("Active", "${stats.activeDaysLast30} / 30", "days", Modifier.weight(1f))
                }
            }
            item {
                Section("This week vs last week") {
                    BarChart(
                        labels = dayNames,
                        readoutLabels = fullDayNames,
                        values = stats.thisWeek,
                        compare = stats.lastWeek,
                        valueName = "This week",
                        compareName = "Last week",
                        color = color,
                    )
                }
            }
            item {
                Section("Last 12 months, by day") {
                    Heatmap(stats.heatmapStart, stats.today, stats.daily, color)
                }
            }
            item {
                Section("Trend") {
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
            if (state.selected == null) {
                item {
                    Section("All time · ${formatDuration(stats.totalSeconds)}") {
                        val byId = state.activities.associateBy { it.id }
                        if (stats.perActivity.isEmpty()) {
                            Text("No time recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            HorizontalBars(
                                stats.perActivity.mapNotNull { t ->
                                    byId[t.activityId]?.let { Triple(it.name, Color(it.color), t.seconds) }
                                },
                            )
                        }
                    }
                }
            } else {
                item {
                    Section("All time") {
                        Text(formatDuration(stats.totalSeconds), style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
    }
}

private fun weekDelta(stats: Stats): String {
    val last = stats.lastWeekTotal
    if (last == 0L) return if (stats.thisWeekTotal > 0) "Last week: none" else "No time yet"
    val pct = ((stats.thisWeekTotal - last) * 100.0 / last).roundToInt()
    return "${if (pct >= 0) "+" else ""}$pct% vs last"
}

@Composable
private fun Tile(title: String, value: String, detail: String, modifier: Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
