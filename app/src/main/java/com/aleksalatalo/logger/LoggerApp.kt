package com.aleksalatalo.logger

import android.app.Application
import android.content.Context
import com.aleksalatalo.logger.data.ActivityRepository
import com.aleksalatalo.logger.data.db.AppDatabase
import com.aleksalatalo.logger.data.db.SettingsEntity
import com.aleksalatalo.logger.domain.SessionEngine
import com.aleksalatalo.logger.system.SessionAlarms
import com.aleksalatalo.logger.system.SessionEffects
import com.aleksalatalo.logger.system.SessionNotifications
import com.aleksalatalo.logger.system.Widgets
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
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
        container.startWidgetUpdates()
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

    val backup = com.aleksalatalo.logger.data.Backup(database)

    val widgets = Widgets(context, sessionEngine, activityRepository, settings)

    /** Re-renders the home-screen widgets whenever sessions, activities or settings change. */
    @OptIn(FlowPreview::class)
    fun startWidgetUpdates() {
        appScope.launch {
            combine(sessionEngine.runningSession, sessionEngine.history, activityRepository.activities, settings) { _, _, _, _ -> }
                .debounce(300)
                .collectLatest { if (widgets.hasAny()) widgets.updateAll() }
        }
    }

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
