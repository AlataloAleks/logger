package dev.tohma.logger.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.tohma.logger.data.db.ActivityEntity
import dev.tohma.logger.data.db.AppDatabase
import dev.tohma.logger.data.db.SessionMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class SessionEngineTest {
    private lateinit var db: AppDatabase
    private lateinit var engine: SessionEngine
    private var now = 1_000_000L
    private var planche = 0L
    private var reading = 0L

    @Before
    fun setUp() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        engine = SessionEngine(db, clock = { now }, zone = { ZoneId.of("Europe/Helsinki") })
        planche = db.activityDao().insert(ActivityEntity(name = "Planche", color = 0, createdAt = 0))
        reading = db.activityDao().insert(ActivityEntity(name = "Reading", color = 0, createdAt = 0))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun stopRecordsActualElapsedTime() = runTest {
        val started = engine.startStopwatch(planche, "  Work on holds ") as StartResult.Started
        now += 80_500 // 1 min 20.5 s
        val stopped = engine.stop()!!
        assertEquals(started.sessionId, stopped.sessionId)
        assertEquals(80, stopped.seconds)
        val s = engine.get(stopped.sessionId)!!
        assertEquals(1_000_000L + 80_500, s.endedAt)
        assertEquals(80L, s.workSeconds)
        assertEquals("Work on holds", s.description)
        assertEquals("Europe/Helsinki", s.zoneId)
        assertNull(engine.running.first())
    }

    @Test
    fun onlyOneSessionCanRun() = runTest {
        engine.startStopwatch(planche, null)
        val second = engine.startStopwatch(reading, null)
        assertTrue(second is StartResult.AlreadyRunning)
        assertEquals("Planche", engine.running.first()!!.activityName)
    }

    @Test
    fun switchStopsCurrentAndStartsNew() = runTest {
        engine.startStopwatch(planche, null)
        now += 30_000
        val (stopped, newId) = engine.switchTo(reading, "Book")
        assertEquals(30L, stopped!!.seconds)
        val running = engine.running.first()!!
        assertEquals(newId, running.session.id)
        assertEquals("Reading", running.activityName)
    }

    @Test
    fun stopWithNothingRunningReturnsNull() = runTest {
        assertNull(engine.stop())
    }

    @Test
    fun clockGoingBackwardsNeverGivesNegativeTime() = runTest {
        engine.startStopwatch(planche, null)
        now -= 60_000
        assertEquals(0L, engine.stop()!!.seconds)
    }

    @Test
    fun discardDeletesRunningSession() = runTest {
        engine.startStopwatch(planche, null)
        engine.discardRunning()
        assertNull(engine.running.first())
        assertTrue(engine.history.first().isEmpty())
    }

    @Test
    fun blankDescriptionIsStoredAsNull() = runTest {
        val id = (engine.startStopwatch(planche, "x") as StartResult.Started).sessionId
        engine.setDescription(id, "   ")
        assertNull(engine.get(id)!!.description)
    }

    @Test
    fun manualEntryIsFinishedSession() = runTest {
        val id = engine.addManual(planche, startedAt = 500_000, seconds = 120, description = null)
        val s = engine.get(id)!!
        assertEquals(SessionMode.MANUAL, s.mode)
        assertEquals(500_000L + 120_000, s.endedAt)
        assertEquals(120L, s.workSeconds)
        assertNull(engine.running.first())
    }

    @Test
    fun editingDurationKeepsStartAndMovesEnd() = runTest {
        engine.startStopwatch(planche, null)
        now += 80_000
        val id = engine.stop()!!.sessionId
        val start = engine.get(id)!!.startedAt
        engine.editFinished(id, reading, start, seconds = 120, description = "Edited")
        val s = engine.get(id)!!
        assertEquals(start, s.startedAt)
        assertEquals(start + 120_000, s.endedAt)
        assertEquals(120L, s.workSeconds)
        assertEquals(reading, s.activityId)
        assertEquals("Edited", s.description)
    }
}
