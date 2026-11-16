package com.aleksalatalo.logger.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "activity_group")
data class ActivityGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "activity",
    foreignKeys = [
        ForeignKey(
            entity = ActivityGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("groupId"), Index(value = ["name"], unique = true)],
)
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val groupId: Long? = null,
    /** ARGB color. */
    val color: Int,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long,
)

enum class SessionMode { STOPWATCH, TIMER, POMODORO, MANUAL }

@Entity(
    tableName = "session",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("startedAt"), Index("activityId", "startedAt"), Index("endedAt")],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    val mode: SessionMode,
    /** UTC epoch millis. */
    val startedAt: Long,
    /** UTC epoch millis; null while the session is running. */
    val endedAt: Long? = null,
    /** Time zone the session was recorded in, used to derive its logical day. */
    val zoneId: String,
    /** Planned length for Timer mode. */
    val plannedSeconds: Long? = null,
    val workSeconds: Long = 0,
    val breakSeconds: Long = 0,
    val description: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class PhaseType { WORK, BREAK }

@Entity(
    tableName = "pomodoro_phase",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class PomodoroPhaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val type: PhaseType,
    val phaseIndex: Int,
    val startedAt: Long,
    val endedAt: Long? = null,
)

/** Single-row table (id is always [SINGLETON_ID]). */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** Hour (0-23) at which a new logical day starts. */
    val dayStartHour: Int = 4,
    /** ISO day of week (1 = Monday ... 7 = Sunday). */
    val weekStart: Int = 1,
    val countBreaks: Boolean = false,
    val pomoWorkMin: Int = 25,
    val pomoBreakMin: Int = 5,
    val pomoLongBreakMin: Int = 15,
    val pomoLongEvery: Int = 4,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
