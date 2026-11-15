package dev.tohma.logger.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.tohma.logger.data.db.ActivityEntity
import dev.tohma.logger.data.db.AppDatabase
import dev.tohma.logger.data.db.PhaseType
import dev.tohma.logger.data.db.SessionMode
import dev.tohma.logger.data.db.SettingsEntity
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
        val started = engine.start(planche, "  Work on holds ") as StartResult.Started
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
        engine.start(planche, null)
        val second = engine.start(reading, null)
        assertTrue(second is StartResult.AlreadyRunning)
        assertEquals("Planche", engine.running.first()!!.activityName)
    }

    @Test
    fun switchStopsCurrentAndStartsNew() = runTest {
        engine.start(planche, null)
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
        engine.start(planche, null)
        now -= 60_000
        assertEquals(0L, engine.stop()!!.seconds)
    }

    @Test
    fun discardDeletesRunningSession() = runTest {
        engine.start(planche, null)
        engine.discardRunning()
        assertNull(engine.running.first())
        assertTrue(engine.history.first().isEmpty())
    }

    @Test
    fun blankDescriptionIsStoredAsNull() = runTest {
        val id = (engine.start(planche, "x") as StartResult.Started).sessionId
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
        engine.start(planche, null)
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

    @Test
    fun timerStoppedEarlyRecordsActualTime() = runTest {
        engine.start(planche, null, timerSeconds = 120)
        now += 80_000 // stopped after 1:20 of a 2:00 timer
        assertEquals(80L, engine.stop()!!.seconds)
    }

    @Test
    fun timerExpiresAtPlannedEnd() = runTest {
        val id = (engine.start(planche, null, timerSeconds = 120) as StartResult.Started).sessionId
        assertEquals(SessionMode.TIMER, engine.get(id)!!.mode)
        now += 119_000
        assertNull(engine.expireIfDue())
        now += 30_000 // alarm delivered late
        assertEquals(120L, engine.expireIfDue()!!.seconds)
        assertEquals(1_000_000L + 120_000, engine.get(id)!!.endedAt)
        // A second caller (alarm and screen racing) finds nothing left to do.
        assertNull(engine.expireIfDue())
    }

    @Test
    fun stoppingAnOverdueTimerNeverExceedsPlan() = runTest {
        engine.start(planche, null, timerSeconds = 60)
        now += 600_000
        assertEquals(60L, engine.stop()!!.seconds)
    }

    @Test
    fun stopwatchNeverExpires() = runTest {
        engine.start(planche, null)
        now += 86_400_000
        assertNull(engine.expireIfDue())
    }

    @Test
    fun stopAtRecordsChosenEnd() = runTest {
        engine.start(planche, null)
        now += 3_600_000
        assertEquals(600L, engine.stopAt(1_000_000L + 600_000)!!.seconds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun stopAtRejectsEndBeforeStart() = runTest {
        engine.start(planche, null)
        now += 60_000
        engine.stopAt(999_000)
    }

    private val min = 60_000L

    @Test
    fun pomodoroStartsWithWorkPhase() = runTest {
        engine.start(planche, null, pomodoro = true)
        val running = engine.runningSession.first()!!
        assertEquals(SessionMode.POMODORO, running.row.session.mode)
        assertEquals(PhaseType.WORK, running.phase!!.type)
        assertEquals(1_000_000L + 25 * min, running.endsAt)
    }

    @Test
    fun pomodoroAdvancesOnSchedule() = runTest {
        engine.start(planche, null, pomodoro = true)
        now += 25 * min - 1
        assertNull(engine.advancePomodoroIfDue())
        now += 10_000 // alarm arrives 10 s late
        assertEquals(PhaseChange(PhaseType.BREAK, 1, 300), engine.advancePomodoroIfDue())
        val running = engine.runningSession.first()!!
        // The break starts when work was planned to end, not when the alarm arrived.
        assertEquals(1_000_000L + 25 * min, running.phase!!.startedAt)
        assertEquals(1_000_000L + 30 * min, running.endsAt)
    }

    @Test
    fun pomodoroCatchesUpMissedPhases() = runTest {
        engine.start(planche, null, pomodoro = true)
        now += 61 * min // work 25, break 5, work 25, break 5, now in round 3
        assertEquals(PhaseChange(PhaseType.WORK, 3, 1500), engine.advancePomodoroIfDue())
        assertEquals(4, engine.runningSession.first()!!.phase!!.phaseIndex)
    }

    @Test
    fun everyFourthBreakIsLong() = runTest {
        engine.start(planche, null, pomodoro = true)
        now += (4 * 25 + 3 * 5) * min // end of the 4th work round
        assertEquals(PhaseChange(PhaseType.BREAK, 4, 900), engine.advancePomodoroIfDue())
    }

    @Test
    fun stoppingPomodoroSumsWorkAndBreak() = runTest {
        engine.start(planche, null, pomodoro = true)
        now += 27 * min // 25 min work, 2 min into the break
        val stopped = engine.stop()!!
        assertEquals(1500L, stopped.seconds)
        val s = engine.get(stopped.sessionId)!!
        assertEquals(1500L, s.workSeconds)
        assertEquals(120L, s.breakSeconds)
        assertNull(engine.runningSession.first())
    }

    @Test
    fun stoppingPomodoroAfterMissedAlarmsCountsEveryPhase() = runTest {
        engine.start(planche, null, pomodoro = true)
        now += 61 * min
        val s = engine.get(engine.stop()!!.sessionId)!!
        assertEquals(51 * 60L, s.workSeconds)
        assertEquals(10 * 60L, s.breakSeconds)
    }

    @Test
    fun pomodoroUsesStoredSettings() = runTest {
        db.settingsDao().upsert(SettingsEntity(pomoWorkMin = 50, pomoBreakMin = 10))
        engine.start(planche, null, pomodoro = true)
        assertEquals(1_000_000L + 50 * min, engine.runningSession.first()!!.endsAt)
    }
}
