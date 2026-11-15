package dev.tohma.logger

import android.app.Application
import dev.tohma.logger.data.ActivityRepository
import dev.tohma.logger.data.db.AppDatabase
import dev.tohma.logger.data.db.SettingsEntity
import dev.tohma.logger.domain.SessionEngine
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
        container = AppContainer(AppDatabase.create(this))
    }
}

/** Manual dependency wiring. */
class AppContainer(val database: AppDatabase) {
    val activityRepository = ActivityRepository(
        activityDao = database.activityDao(),
        groupDao = database.activityGroupDao(),
        sessionDao = database.sessionDao(),
    )
    val sessionEngine = SessionEngine(database)

    /** Application-lifetime scope for writes that must finish after a screen closes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Settings with defaults when the row has not been written yet. */
    val settings: Flow<SettingsEntity> = database.settingsDao().observe().map { it ?: SettingsEntity() }
}
