package com.aleksalatalo.logger.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import com.aleksalatalo.logger.data.ActivityRepository
import com.aleksalatalo.logger.data.db.ActivityEntity
import com.aleksalatalo.logger.data.db.ActivityGroupEntity
import com.aleksalatalo.logger.data.db.SettingsEntity
import com.aleksalatalo.logger.domain.Balance
import com.aleksalatalo.logger.domain.DayEntry
import com.aleksalatalo.logger.domain.SessionEngine
import com.aleksalatalo.logger.domain.Stats
import com.aleksalatalo.logger.domain.StatsPeriod
import com.aleksalatalo.logger.domain.computeBalance
import com.aleksalatalo.logger.domain.computeStats
import com.aleksalatalo.logger.domain.dayEntries
import com.aleksalatalo.logger.domain.formatDuration
import com.aleksalatalo.logger.domain.leisureActivityIds
import com.aleksalatalo.logger.domain.logicalDate
import com.aleksalatalo.logger.domain.periodRanges
import com.aleksalatalo.logger.domain.recentDays
import com.aleksalatalo.logger.domain.totalsIn
import com.aleksalatalo.logger.ui.common.ColorDot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/** Everything the statistics screens are computed from. */
data class StatsInputs(
    val entries: List<DayEntry>,
    val activities: List<ActivityEntity>,
    val groups: List<ActivityGroupEntity>,
    val leisureIds: Set<Long>,
    val today: LocalDate,
    val weekStart: DayOfWeek,
)

fun statsInputs(
    engine: SessionEngine,
    repository: ActivityRepository,
    settings: Flow<SettingsEntity>,
): Flow<StatsInputs> =
    combine(engine.history, repository.activities, repository.groups, settings) { rows, activities, groups, s ->
        StatsInputs(
            entries = dayEntries(rows.map { it.session }, s),
            activities = activities,
            groups = groups,
            leisureIds = leisureActivityIds(activities, groups),
            today = logicalDate(System.currentTimeMillis(), ZoneId.systemDefault().id, s.dayStartHour),
            weekStart = DayOfWeek.of(s.weekStart),
        )
    }

/** One activity in the overview list: total in the period, the previous period, and the last 7 days. */
data class ActivityRow(
    val activity: ActivityEntity,
    val leisure: Boolean,
    val total: Long,
    val previous: Long?,
    val recent: List<Long>,
)

data class GroupBlock(
    val group: ActivityGroupEntity,
    val rows: List<ActivityRow>,
    val total: Long,
    val previous: Long?,
    val recent: List<Long>,
)

data class OverviewState(
    val loading: Boolean = true,
    val period: StatsPeriod = StatsPeriod.WEEK,
    val hasData: Boolean = false,
    /** Statistics of Focus time only. */
    val focus: Stats? = null,
    val focusTotal: Long = 0,
    val focusPrevious: Long? = null,
    val balance: Balance? = null,
    val groups: List<GroupBlock> = emptyList(),
    val ungrouped: List<ActivityRow> = emptyList(),
    val collapsed: Set<Long> = emptySet(),
)

class StatsViewModel(inputs: Flow<StatsInputs>) : ViewModel() {
    private val period = MutableStateFlow(StatsPeriod.WEEK)
    private val collapsed = MutableStateFlow<Set<Long>>(emptySet())

    val state: StateFlow<OverviewState> =
        combine(inputs, period, collapsed) { input, p, c -> overview(input, p, c) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverviewState())

    fun selectPeriod(p: StatsPeriod) {
        period.value = p
    }

    fun toggleGroup(id: Long) {
        collapsed.value = collapsed.value.let { if (id in it) it - id else it + id }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = (this[APPLICATION_KEY] as LoggerApp).container
                StatsViewModel(statsInputs(c.sessionEngine, c.activityRepository, c.settings))
            }
        }
    }
}

private fun overview(input: StatsInputs, period: StatsPeriod, collapsed: Set<Long>): OverviewState {
    val (current, previousRange) = periodRanges(period, input.today, input.weekStart)
    val totals = totalsIn(input.entries, current)
    val previousTotals = previousRange?.let { totalsIn(input.entries, it) }
    val withTime = input.entries.filter { it.seconds > 0 }.map { it.activityId }.toSet()
    val focusEntries = input.entries.filter { it.activityId !in input.leisureIds }

    fun row(a: ActivityEntity) = ActivityRow(
        activity = a,
        leisure = a.id in input.leisureIds,
        total = totals[a.id] ?: 0,
        previous = previousTotals?.let { it[a.id] ?: 0 },
        recent = recentDays(input.entries, setOf(a.id), input.today),
    )

    // Archived activities are listed only when they have tracked time.
    val listed = input.activities.filter { !it.archived || it.id in withTime }
    val groupIds = input.groups.map { it.id }.toSet()
    val blocks = input.groups.mapNotNull { g ->
        val rows = listed.filter { it.groupId == g.id }.map(::row)
        if (rows.isEmpty()) return@mapNotNull null
        GroupBlock(
            group = g,
            rows = rows,
            total = rows.sumOf { it.total },
            previous = previousTotals?.let { rows.sumOf { r -> r.previous ?: 0 } },
            recent = recentDays(input.entries, rows.map { it.activity.id }.toSet(), input.today),
        )
    }
    return OverviewState(
        loading = false,
        period = period,
        hasData = withTime.isNotEmpty(),
        focus = computeStats(focusEntries, input.today, input.weekStart),
        focusTotal = focusEntries.filter { it.day in current }.sumOf { it.seconds },
        focusPrevious = previousRange?.let { r -> focusEntries.filter { it.day in r }.sumOf { it.seconds } },
        balance = computeBalance(input.entries, input.leisureIds, input.today, input.weekStart),
        groups = blocks,
        ungrouped = listed.filter { it.groupId == null || it.groupId !in groupIds }.map(::row),
        collapsed = collapsed,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    onOpenActivity: (Long) -> Unit,
    onOpenGroup: (Long) -> Unit,
    viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
        val focus = state.focus ?: return@Scaffold
        val balance = state.balance ?: return@Scaffold
        val primary = MaterialTheme.colorScheme.primary

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { PeriodSelector(state.period, viewModel::selectPeriod) }
            if (!state.hasData) {
                item { EmptyText("No time recorded yet. Statistics appear once you finish a session.") }
                return@LazyColumn
            }
            item {
                Section("Focus") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tile(
                            periodTitle(state.period),
                            formatDuration(state.focusTotal),
                            changeDetail(state.period, state.focusTotal, state.focusPrevious),
                            Modifier.weight(1f),
                        )
                        Tile("Streak", streakText(focus.currentStreak), "Best ${focus.longestStreak}", Modifier.weight(1f))
                        Tile("Active days", "${focus.activeDaysLast30} / 30", "last 30 days", Modifier.weight(1f))
                    }
                }
            }
            item { BalanceSection(balance) }
            item { HeatmapSection("Focus, last 12 months", focus, primary) }
            item { TrendSection("Focus trend", focus, primary) }
            item { Section("Activities") {} }
            state.groups.forEach { block ->
                val expanded = block.group.id !in state.collapsed
                item(key = "g${block.group.id}") {
                    GroupHeader(
                        block = block,
                        expanded = expanded,
                        onToggle = { viewModel.toggleGroup(block.group.id) },
                        onClick = { onOpenGroup(block.group.id) },
                    )
                }
                if (expanded) {
                    items(block.rows, key = { "a${it.activity.id}" }) { r ->
                        ActivityListRow(r, indent = true, onClick = { onOpenActivity(r.activity.id) })
                    }
                }
            }
            if (state.groups.isNotEmpty() && state.ungrouped.isNotEmpty()) {
                item { HorizontalDivider(Modifier.padding(horizontal = 16.dp)) }
            }
            items(state.ungrouped, key = { "a${it.activity.id}" }) { r ->
                ActivityListRow(r, indent = false, onClick = { onOpenActivity(r.activity.id) })
            }
        }
    }
}

@Composable
private fun BalanceSection(balance: Balance) {
    val focusColor = MaterialTheme.colorScheme.primary
    val leisureColor = leisureColor()
    Section("Focus and leisure") {
        Text("This week", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (balance.focusThisWeek + balance.leisureThisWeek == 0L) {
            Text("No time this week yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            SplitBar(balance.focusThisWeek, balance.leisureThisWeek, "Focus", "Leisure", focusColor, leisureColor)
        }
        val changes = listOfNotNull(
            changeText(balance.focusThisWeek, balance.focusLastWeek)?.let { "Focus $it" },
            changeText(balance.leisureThisWeek, balance.leisureLastWeek)?.let { "Leisure $it" },
        )
        if (changes.isNotEmpty()) {
            Text(
                changes.joinToString(" · ") + " vs last week",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text("Last 12 weeks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BarChart(
            labels = balance.weeks.map { it.label },
            readoutLabels = balance.weeks.map { "Week of ${it.label}" },
            values = balance.weeks.map { it.seconds },
            compare = balance.leisureWeeks,
            valueName = "Focus",
            compareName = "Leisure",
            color = focusColor,
            compareColor = leisureColor,
            labelEvery = 3,
        )
    }
}

/** Total in the period and change vs the previous one, right-aligned. */
@Composable
private fun TotalColumn(total: Long, previous: Long?) {
    Column(Modifier.widthIn(min = 64.dp), horizontalAlignment = Alignment.End) {
        Text(formatDuration(total), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        changeText(total, previous)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun GroupHeader(block: GroupBlock, expanded: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    val color = if (block.group.leisure) leisureColor() else MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggle) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse ${block.group.name}" else "Expand ${block.group.name}",
            )
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                block.group.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (block.group.leisure) Tag("Leisure")
        }
        Spacer(Modifier.width(8.dp))
        Sparkline(block.recent, color)
        Spacer(Modifier.width(12.dp))
        TotalColumn(block.total, block.previous)
    }
}

@Composable
private fun ActivityListRow(row: ActivityRow, indent: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = if (indent) 32.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorDot(row.activity.color, size = 10)
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                row.activity.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (row.leisure) Tag("Leisure")
            if (row.activity.archived) Tag("Archived")
        }
        Spacer(Modifier.width(8.dp))
        Sparkline(row.recent, Color(row.activity.color))
        Spacer(Modifier.width(12.dp))
        TotalColumn(row.total, row.previous)
    }
}
