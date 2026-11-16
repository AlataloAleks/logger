package com.aleksalatalo.logger.system

import com.aleksalatalo.logger.domain.RunningSession
import com.aleksalatalo.logger.domain.SessionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps Android's notification and alarm in line with the database, which is the source of truth.
 * Every change to the running session flows through [SessionEngine.runningSession] and lands in
 * [sync], so screens, receivers and restarts never have to manage notifications or alarms themselves.
 */
class SessionEffects(
    private val engine: SessionEngine,
    private val notifications: SessionNotifications,
    private val alarms: SessionAlarms,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun start(scope: CoroutineScope) {
        scope.launch { engine.runningSession.collect { sync(it) } }
    }

    /** Re-applies the current state, e.g. after a reboot or when the app comes to the foreground. */
    suspend fun refresh() {
        expireDue()
        sync(engine.runningSession.first())
    }

    /**
     * Handles a deadline that has passed: finishes a timer or moves a Pomodoro to its next phase,
     * and alerts the user. Safe to call when nothing is due.
     */
    suspend fun expireDue() {
        val running = engine.running.first() ?: return
        engine.expireIfDue()?.let { stopped ->
            notifications.showTimerFinished(running.activityName, stopped.seconds)
            return
        }
        engine.advancePomodoroIfDue()?.let { change -> notifications.showPhaseChange(running.activityName, change) }
    }

    private suspend fun sync(running: RunningSession?) {
        if (running == null) {
            alarms.cancel()
            notifications.cancelRunning()
            return
        }
        val end = running.endsAt
        if (end != null && end <= clock()) {
            // Due already (the alarm was missed); handling it triggers another sync.
            expireDue()
            return
        }
        if (end != null) alarms.scheduleTimerEnd(end) else alarms.cancel()
        notifications.showRunning(running)
    }
}
