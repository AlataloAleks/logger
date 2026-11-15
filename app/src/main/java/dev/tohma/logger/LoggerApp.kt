package dev.tohma.logger

import android.app.Application
import android.content.Context
import dev.tohma.logger.data.ActivityRepository
import dev.tohma.logger.data.db.AppDatabase
import dev.tohma.logger.data.db.SettingsEntity
import dev.tohma.logger.domain.SessionEngine
import dev.tohma.logger.system.SessionAlarms
import dev.tohma.logger.system.SessionEffects
import dev.tohma.logger.system.SessionNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LoggerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, AppDatabase.create(this))
        container.notifications.createChannels()
        // Must be read before the effects re-post the notification below.
        container.runningNotificationWasShowing = container.notifications.isRunningNotificationShowing()
        container.sessionEffects.start(container.appScope)
    }
}

/** Manual dependency wiring. */
class AppContainer(context: Context, val database: AppDatabase) {
    /** Application-lifetime scope for work that must outlive a screen or a broadcast. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val activityRepository = ActivityRepository(
        activityDao = database.activityDao(),
        groupDao = database.activityGroupDao(),
        sessionDao = database.sessionDao(),
    )
    val sessionEngine = SessionEngine(database)
    val notifications = SessionNotifications(context)
    val alarms = SessionAlarms(context)
    val sessionEffects = SessionEffects(sessionEngine, notifications, alarms)

    /** Settings with defaults when the row has not been written yet. */
    val settings: Flow<SettingsEntity> = database.settingsDao().observe().map { it ?: SettingsEntity() }

    /** Small UI preferences that do not belong in the database. */
    val prefs = UiPrefs(context)

    /**
     * Whether the running-session notification was still visible when this process started.
     * If a stopwatch is running but its notification is gone (force-stop, reboot, swiped away),
     * the user is asked what to do with it.
     */
    var runningNotificationWasShowing = false

    /** The recovery question is asked at most once per process. */
    var recoveryHandled = false
}

class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    var lastTimerSeconds: Long
        get() = prefs.getLong("lastTimerSeconds", 120)
        set(value) = prefs.edit().putLong("lastTimerSeconds", value).apply()
}
