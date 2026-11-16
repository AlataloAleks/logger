package dev.tohma.logger.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.tohma.logger.data.db.AppDatabase
import dev.tohma.logger.data.db.SettingsEntity
import dev.tohma.logger.domain.SessionEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class BackupTest {
    private lateinit var source: AppDatabase
    private lateinit var target: AppDatabase
    private var now = 1_000_000L

    private fun newDb() = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java,
    ).build()

    @Before
    fun setUp() = runTest {
        source = newDb()
        target = newDb()
        val repo = ActivityRepository(source.activityDao(), source.activityGroupDao(), source.sessionDao())
        repo.createGroup("Sport")
        val sport = source.activityGroupDao().observeAll().first().single().id
        repo.createActivity("Planche", sport, 0xFFE53935.toInt())
        repo.createActivity("Reading", null, 0xFF1E88E5.toInt())
        source.settingsDao().upsert(SettingsEntity(dayStartHour = 3, countBreaks = true))
        val engine = SessionEngine(source, clock = { now }, zone = { ZoneId.of("Europe/Helsinki") })
        engine.start(1, "Holds \"with\" quotes\nand a newline", timerSeconds = 120)
        now += 65_000
        engine.stop()
        engine.start(2, null, pomodoro = true)
        now += 27 * 60_000
        engine.stop()
        engine.start(1, null) // still running at export time
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    @Test
    fun roundTripRestoresEverything() = runTest {
        val json = Backup(source).exportJson(exportedAt = 42)
        val restored = Backup(target).importJson(json)
        assertEquals(Backup(source).read(), Backup(target).read())
        assertEquals(2, restored.activities.size)
        assertEquals(3, restored.sessions.size)
        assertTrue(restored.phases.size >= 2)
    }

    @Test
    fun importReplacesExistingData() = runTest {
        ActivityRepository(target.activityDao(), target.activityGroupDao(), target.sessionDao())
            .createActivity("Old", null, 0)
        Backup(target).importJson(Backup(source).exportJson())
        assertEquals(listOf("Planche", "Reading"), target.backupDao().activities().map { it.name }.sorted())
    }

    @Test
    fun invalidFileChangesNothing() = runTest {
        Backup(target).importJson(Backup(source).exportJson())
        val before = Backup(target).read()
        for (bad in listOf("not json", """{"format":"other","version":1}""", brokenReferences())) {
            try {
                Backup(target).importJson(bad)
                fail("Expected InvalidBackupException for $bad")
            } catch (_: InvalidBackupException) {
            }
        }
        assertEquals(before, Backup(target).read())
    }

    private suspend fun brokenReferences(): String =
        Backup(source).exportJson().replace("\"activityId\": 2", "\"activityId\": 99")
}
