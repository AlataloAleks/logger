package dev.tohma.logger.ui.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.tohma.logger.LoggerApp
import dev.tohma.logger.data.ActivityRepository
import dev.tohma.logger.data.NameError
import dev.tohma.logger.data.db.ActivityEntity
import dev.tohma.logger.data.db.ActivityGroupEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ActivitySection(val group: ActivityGroupEntity?, val activities: List<ActivityEntity>)

data class ActivitiesUiState(
    val loading: Boolean = true,
    val sections: List<ActivitySection> = emptyList(),
    val archived: List<ActivityEntity> = emptyList(),
    val groups: List<ActivityGroupEntity> = emptyList(),
    val showArchived: Boolean = false,
    val activityCount: Int = 0,
)

class ActivitiesViewModel(private val repository: ActivityRepository) : ViewModel() {
    private val showArchived = MutableStateFlow(false)

    val state: StateFlow<ActivitiesUiState> =
        combine(repository.activities, repository.groups, showArchived) { activities, groups, showArchived ->
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
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivitiesUiState())

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
                ActivitiesViewModel(app.container.activityRepository)
            }
        }
    }
}
