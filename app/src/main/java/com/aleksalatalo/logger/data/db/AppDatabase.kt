package com.aleksalatalo.logger.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ActivityGroupEntity::class,
        ActivityEntity::class,
        SessionEntity::class,
        PomodoroPhaseEntity::class,
        SettingsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
    abstract fun activityGroupDao(): ActivityGroupDao
    abstract fun sessionDao(): SessionDao
    abstract fun settingsDao(): SettingsDao
    abstract fun pomodoroPhaseDao(): PomodoroPhaseDao
    abstract fun backupDao(): com.aleksalatalo.logger.data.BackupDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "logger.db").build()
    }
}
