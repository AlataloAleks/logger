package dev.tohma.logger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.withTransaction
import dev.tohma.logger.data.db.ActivityEntity
import dev.tohma.logger.data.db.ActivityGroupEntity
import dev.tohma.logger.data.db.AppDatabase
import dev.tohma.logger.data.db.PhaseType
import dev.tohma.logger.data.db.PomodoroPhaseEntity
import dev.tohma.logger.data.db.SessionEntity
import dev.tohma.logger.data.db.SessionMode
import dev.tohma.logger.data.db.SettingsEntity
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Everything in the database, as written to and read from a backup file. */
data class BackupData(
    val settings: SettingsEntity?,
    val groups: List<ActivityGroupEntity>,
    val activities: List<ActivityEntity>,
    val sessions: List<SessionEntity>,
    val phases: List<PomodoroPhaseEntity>,
)

class InvalidBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Dao
interface BackupDao {
    @Query("SELECT * FROM settings WHERE id = 1") suspend fun settings(): SettingsEntity?
    @Query("SELECT * FROM activity_group") suspend fun groups(): List<ActivityGroupEntity>
    @Query("SELECT * FROM activity") suspend fun activities(): List<ActivityEntity>
    @Query("SELECT * FROM session") suspend fun sessions(): List<SessionEntity>
    @Query("SELECT * FROM pomodoro_phase") suspend fun phases(): List<PomodoroPhaseEntity>

    @Query("DELETE FROM pomodoro_phase") suspend fun deletePhases()
    @Query("DELETE FROM session") suspend fun deleteSessions()
    @Query("DELETE FROM activity") suspend fun deleteActivities()
    @Query("DELETE FROM activity_group") suspend fun deleteGroups()
    @Query("DELETE FROM settings") suspend fun deleteSettings()

    @Insert suspend fun insertSettings(s: SettingsEntity)
    @Insert suspend fun insertGroups(list: List<ActivityGroupEntity>)
    @Insert suspend fun insertActivities(list: List<ActivityEntity>)
    @Insert suspend fun insertSessions(list: List<SessionEntity>)
    @Insert suspend fun insertPhases(list: List<PomodoroPhaseEntity>)
}

/**
 * JSON backup of the whole database. Uses Android's built-in org.json, so no extra dependency.
 * Import replaces all data in one transaction and never leaves a half-imported state.
 */
class Backup(private val db: AppDatabase) {
    private val dao = db.backupDao()

    suspend fun read(): BackupData =
        BackupData(dao.settings(), dao.groups(), dao.activities(), dao.sessions(), dao.phases())

    suspend fun exportJson(exportedAt: Long = System.currentTimeMillis()): String = toJson(read(), exportedAt)

    /** Parses and validates [json] completely before touching the database. */
    suspend fun importJson(json: String): BackupData {
        val data = parse(json)
        db.withTransaction {
            dao.deletePhases()
            dao.deleteSessions()
            dao.deleteActivities()
            dao.deleteGroups()
            dao.deleteSettings()
            data.settings?.let { dao.insertSettings(it) }
            dao.insertGroups(data.groups)
            dao.insertActivities(data.activities)
            dao.insertSessions(data.sessions)
            dao.insertPhases(data.phases)
        }
        return data
    }

    companion object {
        const val FORMAT = "logger-backup"
        const val VERSION = 1

        fun toJson(d: BackupData, exportedAt: Long): String = JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", exportedAt)
            d.settings?.let { s ->
                put(
                    "settings",
                    JSONObject()
                        .put("dayStartHour", s.dayStartHour).put("weekStart", s.weekStart)
                        .put("countBreaks", s.countBreaks).put("pomoWorkMin", s.pomoWorkMin)
                        .put("pomoBreakMin", s.pomoBreakMin).put("pomoLongBreakMin", s.pomoLongBreakMin)
                        .put("pomoLongEvery", s.pomoLongEvery),
                )
            }
            put("groups", JSONArray(d.groups.map { JSONObject().put("id", it.id).put("name", it.name).put("sortOrder", it.sortOrder) }))
            put(
                "activities",
                JSONArray(
                    d.activities.map {
                        JSONObject().put("id", it.id).put("name", it.name).putOpt("groupId", it.groupId)
                            .put("color", it.color).put("archived", it.archived).put("sortOrder", it.sortOrder)
                            .put("createdAt", it.createdAt)
                    },
                ),
            )
            put(
                "sessions",
                JSONArray(
                    d.sessions.map {
                        JSONObject().put("id", it.id).put("activityId", it.activityId).put("mode", it.mode.name)
                            .put("startedAt", it.startedAt).putOpt("endedAt", it.endedAt).put("zoneId", it.zoneId)
                            .putOpt("plannedSeconds", it.plannedSeconds).put("workSeconds", it.workSeconds)
                            .put("breakSeconds", it.breakSeconds).putOpt("description", it.description)
                            .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt)
                    },
                ),
            )
            put(
                "phases",
                JSONArray(
                    d.phases.map {
                        JSONObject().put("id", it.id).put("sessionId", it.sessionId).put("type", it.type.name)
                            .put("phaseIndex", it.phaseIndex).put("startedAt", it.startedAt).putOpt("endedAt", it.endedAt)
                    },
                ),
            )
        }.toString(2)

        fun parse(json: String): BackupData {
            try {
                val root = JSONObject(json)
                if (root.optString("format") != FORMAT) throw InvalidBackupException("This is not a Logger backup file.")
                val version = root.getInt("version")
                if (version > VERSION) throw InvalidBackupException("This backup is from a newer version of the app.")

                val settings = root.optJSONObject("settings")?.let {
                    SettingsEntity(
                        dayStartHour = it.getInt("dayStartHour"),
                        weekStart = it.getInt("weekStart"),
                        countBreaks = it.getBoolean("countBreaks"),
                        pomoWorkMin = it.getInt("pomoWorkMin"),
                        pomoBreakMin = it.getInt("pomoBreakMin"),
                        pomoLongBreakMin = it.getInt("pomoLongBreakMin"),
                        pomoLongEvery = it.getInt("pomoLongEvery"),
                    )
                }
                val groups = root.getJSONArray("groups").objects().map {
                    ActivityGroupEntity(it.getLong("id"), it.getString("name"), it.getInt("sortOrder"))
                }
                val activities = root.getJSONArray("activities").objects().map {
                    ActivityEntity(
                        id = it.getLong("id"),
                        name = it.getString("name"),
                        groupId = it.longOrNull("groupId"),
                        color = it.getInt("color"),
                        archived = it.getBoolean("archived"),
                        sortOrder = it.getInt("sortOrder"),
                        createdAt = it.getLong("createdAt"),
                    )
                }
                val sessions = root.getJSONArray("sessions").objects().map {
                    SessionEntity(
                        id = it.getLong("id"),
                        activityId = it.getLong("activityId"),
                        mode = SessionMode.valueOf(it.getString("mode")),
                        startedAt = it.getLong("startedAt"),
                        endedAt = it.longOrNull("endedAt"),
                        zoneId = it.getString("zoneId"),
                        plannedSeconds = it.longOrNull("plannedSeconds"),
                        workSeconds = it.getLong("workSeconds"),
                        breakSeconds = it.getLong("breakSeconds"),
                        description = if (it.isNull("description")) null else it.optString("description"),
                        createdAt = it.getLong("createdAt"),
                        updatedAt = it.getLong("updatedAt"),
                    )
                }
                val phases = root.getJSONArray("phases").objects().map {
                    PomodoroPhaseEntity(
                        id = it.getLong("id"),
                        sessionId = it.getLong("sessionId"),
                        type = PhaseType.valueOf(it.getString("type")),
                        phaseIndex = it.getInt("phaseIndex"),
                        startedAt = it.getLong("startedAt"),
                        endedAt = it.longOrNull("endedAt"),
                    )
                }

                // Check references up front so the import fails cleanly instead of half-way.
                val groupIds = groups.map { it.id }.toSet()
                val activityIds = activities.map { it.id }.toSet()
                val sessionIds = sessions.map { it.id }.toSet()
                if (activities.any { it.groupId != null && it.groupId !in groupIds } ||
                    sessions.any { it.activityId !in activityIds } ||
                    phases.any { it.sessionId !in sessionIds }
                ) {
                    throw InvalidBackupException("The backup file is damaged (broken references).")
                }
                if (activities.map { it.name.lowercase() }.toSet().size != activities.size) {
                    throw InvalidBackupException("The backup file is damaged (duplicate activity names).")
                }
                if (sessions.count { it.endedAt == null } > 1) {
                    throw InvalidBackupException("The backup file is damaged (several running sessions).")
                }
                return BackupData(settings, groups, activities, sessions, phases)
            } catch (e: JSONException) {
                throw InvalidBackupException("The file could not be read as a Logger backup.", e)
            } catch (e: IllegalArgumentException) {
                throw InvalidBackupException("The backup file contains unknown values.", e)
            }
        }

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

        private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)
    }
}
