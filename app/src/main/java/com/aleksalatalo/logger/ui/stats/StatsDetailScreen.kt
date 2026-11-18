package com.aleksalatalo.logger.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aleksalatalo.logger.LoggerApp
import com.aleksalatalo.logger.data.db.ActivityEntity
import com.aleksalatalo.logger.domain.ActivityLevel
import com.aleksalatalo.logger.domain.NextLevel
import com.aleksalatalo.logger.domain.Stats
import com.aleksalatalo.logger.domain.StatsPeriod
import com.aleksalatalo.logger.domain.activityLevels
import com.aleksalatalo.logger.domain.computeStats
import com.aleksalatalo.logger.domain.formatDuration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek

data class DetailState(
    val loading: Boolean = true,
    /** False when the activity or group no longer exists. */
    val found: Boolean = false,
    val title: String = "",
    val isGroup: Boolean = false,
    val leisure: Boolean = false,
    val archived: Boolean = false,
    /** ARGB color of an activity; null for a group. */
    val color: Int? = null,
    val stats: Stats? = null,
    /** Level of a Focus activity with tracked time. */
    val level: ActivityLevel? = null,
    /** A group's activities with time, largest first. */
    val breakdown: List<Pair<ActivityEntity, Long>> = emptyList(),
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
)

class StatsDetailViewModel(inputs: Flow<StatsInputs>, isGroup: Boolean, id: Long) : ViewModel() {
    val state: StateFlow<DetailState> = inputs.map { input ->
        if (isGroup) groupDetail(input, id) else activityDetail(input, id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState())

    companion object {
        fun factory(isGroup: Boolean): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val c = (this[APPLICATION_KEY] as LoggerApp).container
                val handle: SavedStateHandle = createSavedStateHandle()
                StatsDetailViewModel(
                    statsInputs(c.sessionEngine, c.activityRepository, c.settings),
                    isGroup,
                    handle.get<Long>("id") ?: -1L,
                )
            }
        }
    }
}

private fun activityDetail(input: StatsInputs, id: Long): DetailState {
    val activity = input.activities.firstOrNull { it.id == id }
        ?: return DetailState(loading = false, weekStart = input.weekStart)
    val entries = input.entries.filter { it.activityId == id }
    val leisure = id in input.leisureIds
    return DetailState(
        loading = false,
        found = true,
        title = activity.name,
        leisure = leisure,
        archived = activity.archived,
        color = activity.color,
        stats = computeStats(entries, input.today, input.weekStart),
        level = if (leisure) null else activityLevels(entries)[id],
        weekStart = input.weekStart,
    )
}

private fun groupDetail(input: StatsInputs, id: Long): DetailState {
    val group = input.groups.firstOrNull { it.id == id }
        ?: return DetailState(loading = false, isGroup = true, weekStart = input.weekStart)
    // Archived activities still count toward their group's history.
    val members = input.activities.filter { it.groupId == id }.associateBy { it.id }
    val stats = computeStats(input.entries.filter { it.activityId in members }, input.today, input.weekStart)
    return DetailState(
        loading = false,
        found = true,
        title = group.name,
        isGroup = true,
        leisure = group.leisure,
        stats = stats,
        breakdown = stats.perActivity.filter { it.seconds > 0 }.mapNotNull { t -> members[t.activityId]?.let { it to t.seconds } },
        weekStart = input.weekStart,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsDetailScreen(
    isGroup: Boolean,
    onBack: () -> Unit,
    onOpenActivity: (Long) -> Unit,
    viewModel: StatsDetailViewModel = viewModel(factory = StatsDetailViewModel.factory(isGroup)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        if (state.loading) return@Scaffold
        val stats = state.stats
        val activityColor = state.color
        val color = when {
            activityColor != null -> Color(activityColor)
            state.leisure -> leisureColor()
            else -> MaterialTheme.colorScheme.primary
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!state.found || stats == null) {
                item { EmptyText(if (state.isGroup) "This group no longer exists." else "This activity no longer exists.") }
                return@LazyColumn
            }
            val tags = listOfNotNull(
                if (state.isGroup) "Group" else null,
                if (state.leisure) "Leisure" else "Focus",
                if (state.archived) "Archived" else null,
            )
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { Tag(it) }
                }
            }
            if (stats.totalSeconds == 0L) {
                item { EmptyText("No time recorded yet.") }
                return@LazyColumn
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile(
                        "This week",
                        formatDuration(stats.thisWeekTotal),
                        changeDetail(StatsPeriod.WEEK, stats.thisWeekTotal, stats.lastWeekTotal),
                        Modifier.weight(1f),
                    )
                    Tile("Streak", streakText(stats.currentStreak), "Best ${stats.longestStreak}", Modifier.weight(1f))
                    Tile("All time", formatDuration(stats.totalSeconds), "${stats.activeDaysLast30} of last 30 days", Modifier.weight(1f))
                }
            }
            state.level?.let { level -> item { LevelSection(level, color) } }
            item { WeekCompareSection(stats, state.weekStart, color) }
            item { HeatmapSection("Last 12 months, by day", stats, color) }
            item { TrendSection("Trend", stats, color) }
            if (state.isGroup && state.breakdown.isNotEmpty()) {
                item {
                    Section("Activities, all time") {
                        HorizontalBars(
                            state.breakdown.map { (a, seconds) -> Triple(a.name, Color(a.color), seconds) },
                            onClick = { i -> onOpenActivity(state.breakdown[i].first.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelSection(level: ActivityLevel, color: Color) {
    Section("Level") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Level ${level.level}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                when (val next = level.next) {
                    null -> "Max level"
                    is NextLevel.Time -> "${formatDuration(next.seconds)} to level ${level.level + 1}"
                    is NextLevel.Days -> "${next.days} ${if (next.days == 1) "day" else "days"} to level ${level.level + 1}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { level.progress },
            modifier = Modifier.fillMaxWidth(),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}
