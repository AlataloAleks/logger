package com.aleksalatalo.logger.ui.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aleksalatalo.logger.LoggerApp
import com.aleksalatalo.logger.UiPrefs
import com.aleksalatalo.logger.data.ActivityRepository
import com.aleksalatalo.logger.data.NameError
import com.aleksalatalo.logger.data.db.ActivityEntity
import com.aleksalatalo.logger.data.db.ActivityGroupEntity
import com.aleksalatalo.logger.data.db.SessionEntity
import com.aleksalatalo.logger.data.db.SettingsEntity
import com.aleksalatalo.logger.domain.RunningSession
import com.aleksalatalo.logger.domain.ActivityLevel
import com.aleksalatalo.logger.domain.activityLevels
import com.aleksalatalo.logger.domain.dayEntries
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.aleksalatalo.logger.domain.SessionEngine
import com.aleksalatalo.logger.domain.StartResult
import com.aleksalatalo.logger.domain.StoppedSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Pomodoro lengths chosen when starting one. */
data class Pomodoro(val workMin: Int, val breakMin: Int)

data class ActivitySection(val group: ActivityGroupEntity?, val activities: List<ActivityEntity>)

data class ActivitiesUiState(
    val loading: Boolean = true,
    val sections: List<ActivitySection> = emptyList(),
    val archived: List<ActivityEntity> = emptyList(),
    val groups: List<ActivityGroupEntity> = emptyList(),
    val showArchived: Boolean = false,
    val activityCount: Int = 0,
    val running: RunningSession? = null,
)

class ActivitiesViewModel(
    private val repository: ActivityRepository,
    private val engine: SessionEngine,
    private val prefs: UiPrefs,
    private val settings: Flow<SettingsEntity>,
    private val saveSettings: suspend (SettingsEntity) -> Unit,
) : ViewModel() {
    private val showArchived = MutableStateFlow(false)

    val state: StateFlow<ActivitiesUiState> =
        combine(repository.activities, repository.groups, showArchived, engine.runningSession) { activities, groups, showArchived, running ->
            val active = activities.filterNot { it.archived }
            val groupIds = groups.map { it.id }.toSet()
            val sections = buildList {
                // An activity whose group no longer exists is treated as ungrouped.
                val ungrouped = active.filter { it.groupId == null || it.groupId !in groupIds }
                if (ungrouped.isNotEmpty()) add(ActivitySection(null, ungrouped))
                groups.forEach { group ->
                    val inGroup = active.filter { it.groupId == group.id }
                    if (inGroup.isNotEmpty()) add(ActivitySection(group, inGroup))
                }
            }
            ActivitiesUiState(
                loading = false,
                sections = sections,
                archived = activities.filter { it.archived },
                groups = groups,
                showArchived = showArchived,
                activityCount = activities.size,
                running = running,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivitiesUiState())

    /** Last timer length used, offered as the default next time. */
    val lastTimerSeconds: Long get() = prefs.lastTimerSeconds

    /**
     * Starts a stopwatch, or a timer when [timerSeconds] is given. Calls [onConflict] instead of
     * starting when another session is already running.
     */
    fun start(
        activity: ActivityEntity,
        description: String?,
        timerSeconds: Long?,
        pomodoro: Pomodoro?,
        onStarted: () -> Unit,
        onConflict: (SessionEntity) -> Unit,
    ) {
        timerSeconds?.let { prefs.lastTimerSeconds = it }
        viewModelScope.launch {
            pomodoro?.let { saveDefaults(it) }
            when (val result = engine.start(activity.id, description, timerSeconds, pomodoro != null)) {
                is StartResult.Started -> onStarted()
                is StartResult.AlreadyRunning -> onConflict(result.running)
            }
        }
    }

    fun switchTo(
        activity: ActivityEntity,
        description: String?,
        timerSeconds: Long?,
        pomodoro: Pomodoro?,
        onStarted: () -> Unit,
    ) {
        viewModelScope.launch {
            pomodoro?.let { saveDefaults(it) }
            engine.switchTo(activity.id, description, timerSeconds, pomodoro != null)
            onStarted()
        }
    }

    /** Current Pomodoro lengths, offered as defaults when starting one. */
    val pomodoroDefaults: StateFlow<Pomodoro> = settings
        .map { Pomodoro(it.pomoWorkMin, it.pomoBreakMin) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Pomodoro(25, 5))

    /** Pomodoro lengths are settings: the next Pomodoro starts with the same values. */
    private suspend fun saveDefaults(p: Pomodoro) {
        val current = settings.first()
        if (current.pomoWorkMin != p.workMin || current.pomoBreakMin != p.breakMin) {
            saveSettings(current.copy(pomoWorkMin = p.workMin, pomoBreakMin = p.breakMin))
        }
    }

    /** Level of every activity; activities without tracked time are level 1. */
    val levels: StateFlow<Map<Long, ActivityLevel>> = combine(engine.history, settings) { rows, s ->
        activityLevels(dayEntries(rows.map { it.session }, s))
    }.onEach(::detectLevelUp).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** "Planche reached level 12", emitted when one activity's level rises. */
    val levelUps = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Kept across re-subscriptions, so a level-up that happened on another screen is still noticed. */
    private var previousLevels: Map<Long, ActivityLevel>? = null

    private suspend fun detectLevelUp(current: Map<Long, ActivityLevel>) {
        val previous = previousLevels
        previousLevels = current
        if (previous == null) return
        val ups = current.filter { (id, l) -> l.level > (previous[id]?.level ?: 1) }
        // Several at once means an import or bulk change, not something to celebrate.
        val (id, level) = ups.entries.singleOrNull() ?: return
        val name = repository.activities.first().firstOrNull { it.id == id }?.name ?: return
        levelUps.tryEmit("$name reached level ${level.level}")
    }

    fun stop(onStopped: (StoppedSession) -> Unit) {
        viewModelScope.launch { engine.stop()?.let(onStopped) }
    }

    fun toggleShowArchived() {
        showArchived.value = !showArchived.value
    }

    fun saveActivity(id: Long?, name: String, groupId: Long?, color: Int, onResult: (NameError?) -> Unit) {
        viewModelScope.launch {
            val error = if (id == null) {
                repository.createActivity(name, groupId, color)
            } else {
                repository.updateActivity(id, name, groupId, color)
            }
            onResult(error)
        }
    }

    fun setArchived(activity: ActivityEntity, archived: Boolean) {
        viewModelScope.launch { repository.setArchived(activity.id, archived) }
    }

    fun loadSessionCount(activity: ActivityEntity, onResult: (Int) -> Unit) {
        viewModelScope.launch { onResult(repository.sessionCount(activity.id)) }
    }

    fun deleteActivity(activity: ActivityEntity) {
        viewModelScope.launch { repository.deleteActivity(activity.id) }
    }

    fun createGroup(name: String, onResult: (NameError?) -> Unit) {
        viewModelScope.launch { onResult(repository.createGroup(name)) }
    }

    fun renameGroup(group: ActivityGroupEntity, name: String, onResult: (NameError?) -> Unit) {
        viewModelScope.launch { onResult(repository.renameGroup(group, name)) }
    }

    fun deleteGroup(group: ActivityGroupEntity) {
        viewModelScope.launch { repository.deleteGroup(group) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as LoggerApp
                ActivitiesViewModel(
                    app.container.activityRepository,
                    app.container.sessionEngine,
                    app.container.prefs,
                    app.container.settings,
                    app.container.database.settingsDao()::upsert,
                )
            }
        }
    }
}
