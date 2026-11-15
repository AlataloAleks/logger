package dev.tohma.logger.data.db

import androidx.room.Dao
import androidx.room.Delete
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
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1")
    fun observe(): Flow<SettingsEntity?>

    @Upsert
    suspend fun upsert(settings: SettingsEntity)
}
