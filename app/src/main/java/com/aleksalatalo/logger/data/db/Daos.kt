package com.aleksalatalo.logger.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activity ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activity WHERE id = :id")
    suspend fun get(id: Long): ActivityEntity?

    @Query("SELECT * FROM activity WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): ActivityEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM activity")
    suspend fun nextSortOrder(): Int

    @Insert
    suspend fun insert(activity: ActivityEntity): Long

    @Update
    suspend fun update(activity: ActivityEntity)

    @Delete
    suspend fun delete(activity: ActivityEntity)
}

@Dao
interface ActivityGroupDao {
    @Query("SELECT * FROM activity_group ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<ActivityGroupEntity>>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM activity_group")
    suspend fun nextSortOrder(): Int

    @Insert
    suspend fun insert(group: ActivityGroupEntity): Long

    @Update
    suspend fun update(group: ActivityGroupEntity)

    @Delete
    suspend fun delete(group: ActivityGroupEntity)
}

@Dao
interface SessionDao {
    @Query("SELECT COUNT(*) FROM session WHERE activityId = :activityId")
    suspend fun countForActivity(activityId: Long): Int

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Query("DELETE FROM session WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun get(id: Long): SessionEntity?

    @Query("SELECT * FROM session WHERE endedAt IS NULL LIMIT 1")
    suspend fun getRunning(): SessionEntity?

    @Query("$SESSION_ROW_SELECT WHERE s.endedAt IS NULL LIMIT 1")
    fun observeRunning(): Flow<SessionRow?>

    @Query("$SESSION_ROW_SELECT ORDER BY s.startedAt DESC")
    fun observeAll(): Flow<List<SessionRow>>

    @Query("$SESSION_ROW_SELECT WHERE s.id = :id")
    fun observe(id: Long): Flow<SessionRow?>

    companion object {
        const val SESSION_ROW_SELECT =
            "SELECT s.*, a.name AS activityName, a.color AS activityColor " +
                "FROM session s JOIN activity a ON a.id = s.activityId"
    }
}

/** A session together with the activity fields the UI needs. */
data class SessionRow(
    @Embedded val session: SessionEntity,
    val activityName: String,
    val activityColor: Int,
)

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1")
    fun observe(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun get(): SettingsEntity?

    @Upsert
    suspend fun upsert(settings: SettingsEntity)
}

@Dao
interface PomodoroPhaseDao {
    @Insert
    suspend fun insert(phase: PomodoroPhaseEntity): Long

    @Update
    suspend fun update(phase: PomodoroPhaseEntity)

    @Query("SELECT * FROM pomodoro_phase WHERE sessionId = :sessionId AND endedAt IS NULL LIMIT 1")
    suspend fun getOpen(sessionId: Long): PomodoroPhaseEntity?

    @Query("SELECT * FROM pomodoro_phase WHERE endedAt IS NULL LIMIT 1")
    fun observeOpen(): Flow<PomodoroPhaseEntity?>

    @Query("SELECT * FROM pomodoro_phase WHERE sessionId = :sessionId ORDER BY phaseIndex")
    suspend fun forSession(sessionId: Long): List<PomodoroPhaseEntity>
}
