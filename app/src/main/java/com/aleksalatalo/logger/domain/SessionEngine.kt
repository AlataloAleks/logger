package com.aleksalatalo.logger.domain

import androidx.room.withTransaction
import com.aleksalatalo.logger.data.db.AppDatabase
import com.aleksalatalo.logger.data.db.PhaseType
import com.aleksalatalo.logger.data.db.PomodoroPhaseEntity
import com.aleksalatalo.logger.data.db.SettingsEntity
import com.aleksalatalo.logger.data.db.SessionEntity
import com.aleksalatalo.logger.data.db.SessionMode
import com.aleksalatalo.logger.data.db.SessionRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.ZoneId

data class StoppedSession(val sessionId: Long, val seconds: Long)

/** The running session plus what the UI and alarms need: the open Pomodoro phase and the next deadline. */
data class RunningSession(
    val row: SessionRow,
    val phase: PomodoroPhaseEntity?,
    /** End of the timer or of the current Pomodoro phase; null for a stopwatch. */
    val endsAt: Long?,
)

/** A Pomodoro session moved on to a new phase of [seconds] planned length. */
data class PhaseChange(val type: PhaseType, val round: Int, val seconds: Long)

sealed interface StartResult {
    data class Started(val sessionId: Long) : StartResult
    /** Another session is running; nothing was changed. */
    data class AlreadyRunning(val running: SessionEntity) : StartResult
}

/** End time of a running timer, or null for other modes. */
val SessionEntity.timerEndsAt: Long?
    get() = if (mode == SessionMode.TIMER && plannedSeconds != null) startedAt + plannedSeconds * 1000 else null

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
    private val phaseDao = db.pomodoroPhaseDao()
    private val settingsDao = db.settingsDao()

    val running: Flow<SessionRow?> = dao.observeRunning()

    val runningSession: Flow<RunningSession?> =
        combine(dao.observeRunning(), phaseDao.observeOpen(), settingsDao.observe()) { row, openPhase, stored ->
            if (row == null) return@combine null
            val settings = stored ?: SettingsEntity()
            val phase = openPhase?.takeIf { it.sessionId == row.session.id }
            val endsAt = row.session.timerEndsAt
                ?: phase?.let { it.startedAt + settings.pomodoroPhaseSeconds(it.phaseIndex) * 1000 }
            RunningSession(row, phase, endsAt)
        }
    val history: Flow<List<SessionRow>> = dao.observeAll()
    fun observe(id: Long): Flow<SessionRow?> = dao.observe(id)
    suspend fun get(id: Long): SessionEntity? = dao.get(id)
    suspend fun getRunning(): SessionEntity? = dao.getRunning()

    /** Starts a stopwatch, a timer when [timerSeconds] is given, or a Pomodoro when [pomodoro] is set. */
    suspend fun start(
        activityId: Long,
        description: String?,
        timerSeconds: Long? = null,
        pomodoro: Boolean = false,
    ): StartResult = db.withTransaction {
        dao.getRunning()?.let { return@withTransaction StartResult.AlreadyRunning(it) }
        StartResult.Started(insertRunning(activityId, description, timerSeconds, pomodoro))
    }

    /** Stops the running session (if any) and starts a new one, atomically. */
    suspend fun switchTo(
        activityId: Long,
        description: String?,
        timerSeconds: Long? = null,
        pomodoro: Boolean = false,
    ): Pair<StoppedSession?, Long> = db.withTransaction {
        val stopped = dao.getRunning()?.let { finish(it, clock()) }
        stopped to insertRunning(activityId, description, timerSeconds, pomodoro)
    }

    /** Stops the running session now. Returns null when nothing was running. */
    suspend fun stop(): StoppedSession? = db.withTransaction {
        dao.getRunning()?.let { finish(it, clock()) }
    }

    /** Ends the running session at an earlier moment, for sessions left running by mistake. */
    suspend fun stopAt(endedAt: Long): StoppedSession? = db.withTransaction {
        val s = dao.getRunning() ?: return@withTransaction null
        require(endedAt in s.startedAt..clock()) { "End time must be between start and now" }
        finish(s, endedAt)
    }

    /**
     * Finishes the running timer if its time is up, recording exactly the planned duration.
     * Safe to call repeatedly and from several places: only the first call finishes it.
     */
    suspend fun expireIfDue(): StoppedSession? = db.withTransaction {
        val s = dao.getRunning() ?: return@withTransaction null
        val end = s.timerEndsAt ?: return@withTransaction null
        if (clock() < end) null else finish(s, end)
    }

    /**
     * Moves a running Pomodoro past every phase whose time is up. Each new phase starts exactly
     * when the previous one was planned to end, so late alarms do not shift the schedule.
     * Returns the phase now in progress, or null if nothing changed.
     */
    suspend fun advancePomodoroIfDue(): PhaseChange? = db.withTransaction {
        val s = dao.getRunning()?.takeIf { it.mode == SessionMode.POMODORO } ?: return@withTransaction null
        val settings = settings()
        val current = advancePhases(s.id, until = clock(), settings) ?: return@withTransaction null
        // Touch the session so observers of the running session see the new phase.
        dao.update(s.copy(updatedAt = clock()))
        PhaseChange(
            pomodoroPhaseType(current.phaseIndex),
            pomodoroRound(current.phaseIndex),
            settings.pomodoroPhaseSeconds(current.phaseIndex),
        )
    }

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

    private suspend fun insertRunning(
        activityId: Long,
        description: String?,
        timerSeconds: Long?,
        pomodoro: Boolean,
    ): Long {
        require(timerSeconds == null || timerSeconds > 0) { "Timer duration must be positive" }
        require(!(pomodoro && timerSeconds != null)) { "A session is either a timer or a Pomodoro" }
        val now = clock()
        val id = dao.insert(
            SessionEntity(
                activityId = activityId,
                mode = when {
                    pomodoro -> SessionMode.POMODORO
                    timerSeconds != null -> SessionMode.TIMER
                    else -> SessionMode.STOPWATCH
                },
                startedAt = now,
                zoneId = zone().id,
                plannedSeconds = timerSeconds,
                description = description.clean(),
                createdAt = now,
                updatedAt = now,
            ),
        )
        if (pomodoro) {
            phaseDao.insert(PomodoroPhaseEntity(sessionId = id, type = PhaseType.WORK, phaseIndex = 0, startedAt = now))
        }
        return id
    }

    /**
     * Records the actual elapsed time, never more than a timer's planned length. For a Pomodoro,
     * work and break time are summed separately from its phases.
     */
    private suspend fun finish(s: SessionEntity, requestedEnd: Long): StoppedSession {
        // Guard against the clock having moved backwards since the session started.
        var end = maxOf(requestedEnd, s.startedAt)
        s.timerEndsAt?.let { end = minOf(end, it) }
        if (s.mode != SessionMode.POMODORO) {
            val seconds = (end - s.startedAt) / 1000
            dao.update(s.copy(endedAt = end, workSeconds = seconds, updatedAt = clock()))
            return StoppedSession(s.id, seconds)
        }
        advancePhases(s.id, until = end, settings())
        phaseDao.getOpen(s.id)?.let { phaseDao.update(it.copy(endedAt = maxOf(end, it.startedAt))) }
        val phases = phaseDao.forSession(s.id)
        fun total(type: PhaseType) =
            phases.filter { it.type == type }.sumOf { (it.endedAt ?: end) - it.startedAt } / 1000
        val work = total(PhaseType.WORK)
        dao.update(s.copy(endedAt = end, workSeconds = work, breakSeconds = total(PhaseType.BREAK), updatedAt = clock()))
        return StoppedSession(s.id, work)
    }

    /** Closes every phase that ended before [until] and opens the next. Returns the new open phase, if any. */
    private suspend fun advancePhases(sessionId: Long, until: Long, settings: SettingsEntity): PomodoroPhaseEntity? {
        var phase = phaseDao.getOpen(sessionId) ?: return null
        var changed = false
        while (true) {
            val end = phase.startedAt + settings.pomodoroPhaseSeconds(phase.phaseIndex) * 1000
            if (end > until) break
            phaseDao.update(phase.copy(endedAt = end))
            val next = phase.phaseIndex + 1
            val nextPhase = PomodoroPhaseEntity(
                sessionId = sessionId,
                type = pomodoroPhaseType(next),
                phaseIndex = next,
                startedAt = end,
            )
            phase = nextPhase.copy(id = phaseDao.insert(nextPhase))
            changed = true
        }
        return if (changed) phase else null
    }

    private suspend fun settings(): SettingsEntity = settingsDao.get() ?: SettingsEntity()

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
