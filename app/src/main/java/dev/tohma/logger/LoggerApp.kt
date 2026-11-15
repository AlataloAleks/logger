package dev.tohma.logger

import android.app.Application
import dev.tohma.logger.data.ActivityRepository
import dev.tohma.logger.data.db.AppDatabase

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
}
