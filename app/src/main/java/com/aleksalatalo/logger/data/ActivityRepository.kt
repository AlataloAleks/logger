package com.aleksalatalo.logger.data

import com.aleksalatalo.logger.data.db.ActivityDao
import com.aleksalatalo.logger.data.db.ActivityEntity
import com.aleksalatalo.logger.data.db.ActivityGroupDao
import com.aleksalatalo.logger.data.db.ActivityGroupEntity
import com.aleksalatalo.logger.data.db.SessionDao
import kotlinx.coroutines.flow.Flow

enum class NameError { BLANK, DUPLICATE }

class ActivityRepository(
    private val activityDao: ActivityDao,
    private val groupDao: ActivityGroupDao,
    private val sessionDao: SessionDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val activities: Flow<List<ActivityEntity>> = activityDao.observeAll()
    val groups: Flow<List<ActivityGroupEntity>> = groupDao.observeAll()

    /** Returns null on success. */
    suspend fun createActivity(name: String, groupId: Long?, color: Int): NameError? {
        val clean = name.trim()
        validateName(clean, excludeId = null)?.let { return it }
        activityDao.insert(
            ActivityEntity(
                name = clean,
                groupId = groupId,
                color = color,
                sortOrder = activityDao.nextSortOrder(),
                createdAt = clock(),
            ),
        )
        return null
    }

    /** Returns null on success. */
    suspend fun updateActivity(id: Long, name: String, groupId: Long?, color: Int): NameError? {
        val existing = activityDao.get(id) ?: return null
        val clean = name.trim()
        validateName(clean, excludeId = id)?.let { return it }
        activityDao.update(existing.copy(name = clean, groupId = groupId, color = color))
        return null
    }

    suspend fun setArchived(id: Long, archived: Boolean) {
        val existing = activityDao.get(id) ?: return
        activityDao.update(existing.copy(archived = archived))
    }

    suspend fun sessionCount(activityId: Long): Int = sessionDao.countForActivity(activityId)

    /** Deletes the activity and, through the foreign key cascade, all of its sessions. */
    suspend fun deleteActivity(id: Long) {
        val existing = activityDao.get(id) ?: return
        activityDao.delete(existing)
    }

    /** Returns null on success. */
    suspend fun createGroup(name: String): NameError? {
        val clean = name.trim()
        if (clean.isEmpty()) return NameError.BLANK
        groupDao.insert(ActivityGroupEntity(name = clean, sortOrder = groupDao.nextSortOrder()))
        return null
    }

    /** Returns null on success. */
    suspend fun renameGroup(group: ActivityGroupEntity, name: String): NameError? {
        val clean = name.trim()
        if (clean.isEmpty()) return NameError.BLANK
        groupDao.update(group.copy(name = clean))
        return null
    }

    /** Activities in the group become ungrouped (foreign key SET NULL). */
    suspend fun deleteGroup(group: ActivityGroupEntity) = groupDao.delete(group)

    private suspend fun validateName(name: String, excludeId: Long?): NameError? {
        if (name.isEmpty()) return NameError.BLANK
        val clash = activityDao.findByName(name)
        return if (clash != null && clash.id != excludeId) NameError.DUPLICATE else null
    }
}
