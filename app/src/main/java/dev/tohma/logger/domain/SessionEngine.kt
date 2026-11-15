package dev.tohma.logger.domain

import androidx.room.withTransaction
import dev.tohma.logger.data.db.AppDatabase
import dev.tohma.logger.data.db.SessionEntity
import dev.tohma.logger.data.db.SessionMode
import dev.tohma.logger.data.db.SessionRow
import kotlinx.coroutines.flow.Flow
import java.time.ZoneId

data class StoppedSession(val sessionId: Long, val seconds: Long)

sealed interface StartResult {
    data class Started(val sessionId: Long) : StartResult
    /** Another session is running; nothing was changed. */
    data class AlreadyRunning(val running: SessionEntity) : StartResult
}

/**
 * Single place for all session writes. Elapsed time is always derived from persisted
 * timestamps, so a running session survives the app being closed or killed.
 */
class SessionEngine(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val dao = db.sessionDao()

    val running: Flow<SessionRow?> = dao.observeRunning()
    val history: Flow<List<SessionRow>> = dao.observeAll()
    fun observe(id: Long): Flow<SessionRow?> = dao.observe(id)
    suspend fun get(id: Long): SessionEntity? = dao.get(id)

    suspend fun startStopwatch(activityId: Long, description: String?): StartResult = db.withTransaction {
        dao.getRunning()?.let { return@withTransaction StartResult.AlreadyRunning(it) }
        StartResult.Started(insertRunning(activityId, description))
    }

    /** Stops the running session (if any) and starts a new one, atomically. */
    suspend fun switchTo(activityId: Long, description: String?): Pair<StoppedSession?, Long> = db.withTransaction {
        val stopped = stopRunningInternal()
        stopped to insertRunning(activityId, description)
    }

    /** Returns null when nothing was running. */
    suspend fun stop(): StoppedSession? = db.withTransaction { stopRunningInternal() }

    suspend fun discardRunning() = db.withTransaction {
        dao.getRunning()?.let { dao.delete(it.id) }
    }

    /** Works for running and finished sessions. Blank text clears the description. */
    suspend fun setDescription(sessionId: Long, description: String?) = db.withTransaction {
        val s = dao.get(sessionId) ?: return@withTransaction
        val clean = description.clean()
        if (s.description != clean) dao.update(s.copy(description = clean, updatedAt = clock()))
    }

    suspend fun addManual(activityId: Long, startedAt: Long, seconds: Long, description: String?): Long {
        require(seconds > 0) { "Duration must be positive" }
        val now = clock()
        return dao.insert(
            SessionEntity(
                activityId = activityId,
                mode = SessionMode.MANUAL,
                startedAt = startedAt,
                endedAt = startedAt + seconds * 1000,
                zoneId = zone().id,
                workSeconds = seconds,
                description = description.clean(),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /**
     * Edits a finished session. The duration becomes the tracked work time and the end
     * time is moved to match, so the session keeps its start (and therefore its day).
     */
    suspend fun editFinished(
        sessionId: Long,
        activityId: Long,
        startedAt: Long,
        seconds: Long,
        description: String?,
    ) = db.withTransaction {
        require(seconds > 0) { "Duration must be positive" }
        val s = dao.get(sessionId) ?: return@withTransaction
        check(s.endedAt != null) { "Cannot edit a running session's duration" }
        dao.update(
            s.copy(
                activityId = activityId,
                startedAt = startedAt,
                endedAt = startedAt + (seconds + s.breakSeconds) * 1000,
                workSeconds = seconds,
                description = description.clean(),
                updatedAt = clock(),
            ),
        )
    }

    suspend fun delete(sessionId: Long) = dao.delete(sessionId)

    private suspend fun insertRunning(activityId: Long, description: String?): Long {
        val now = clock()
        return dao.insert(
            SessionEntity(
                activityId = activityId,
                mode = SessionMode.STOPWATCH,
                startedAt = now,
                zoneId = zone().id,
                description = description.clean(),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private suspend fun stopRunningInternal(): StoppedSession? {
        val s = dao.getRunning() ?: return null
        val now = clock()
        // Guard against the clock having moved backwards since the session started.
        val end = maxOf(now, s.startedAt)
        val seconds = (end - s.startedAt) / 1000
        dao.update(s.copy(endedAt = end, workSeconds = seconds, updatedAt = now))
        return StoppedSession(s.id, seconds)
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
