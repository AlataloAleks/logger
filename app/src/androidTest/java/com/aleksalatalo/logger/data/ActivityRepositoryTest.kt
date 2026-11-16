package com.aleksalatalo.logger.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aleksalatalo.logger.data.db.AppDatabase
import com.aleksalatalo.logger.data.db.SessionEntity
import com.aleksalatalo.logger.data.db.SessionMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ActivityRepository(db.activityDao(), db.activityGroupDao(), db.sessionDao(), clock = { 1_000L })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun createTrimsNameAndRejectsBlank() = runTest {
        assertEquals(NameError.BLANK, repo.createActivity("   ", null, 0))
        assertNull(repo.createActivity("  Planche  ", null, 0))
        assertEquals(listOf("Planche"), repo.activities.first().map { it.name })
    }

    @Test
    fun duplicateNamesAreRejectedCaseInsensitively() = runTest {
        assertNull(repo.createActivity("Reading", null, 0))
        assertEquals(NameError.DUPLICATE, repo.createActivity("reading", null, 0))
        assertNull(repo.createActivity("YouTube", null, 0))
        val youtube = repo.activities.first().single { it.name == "YouTube" }
        assertEquals(NameError.DUPLICATE, repo.updateActivity(youtube.id, "READING", null, 0))
        // Renaming to its own name with different case is allowed.
        assertNull(repo.updateActivity(youtube.id, "Youtube", null, 0))
    }

    @Test
    fun sortOrderIncreases() = runTest {
        repo.createActivity("B", null, 0)
        repo.createActivity("A", null, 0)
        assertEquals(listOf("B", "A"), repo.activities.first().map { it.name })
    }

    @Test
    fun archiveAndUnarchive() = runTest {
        repo.createActivity("Exercise", null, 0)
        val id = repo.activities.first().single().id
        repo.setArchived(id, true)
        assertTrue(repo.activities.first().single().archived)
        repo.setArchived(id, false)
        assertEquals(false, repo.activities.first().single().archived)
    }

    @Test
    fun deletingGroupUngroupsActivities() = runTest {
        repo.createGroup("Sport")
        val group = repo.groups.first().single()
        repo.createActivity("Planche", group.id, 0)
        repo.deleteGroup(group)
        assertTrue(repo.groups.first().isEmpty())
        assertNull(repo.activities.first().single().groupId)
    }

    @Test
    fun deletingActivityDeletesItsSessions() = runTest {
        repo.createActivity("Programming", null, 0)
        repo.createActivity("Reading", null, 0)
        val (programming, reading) = repo.activities.first()
        listOf(programming.id, programming.id, reading.id).forEach { activityId ->
            db.sessionDao().insert(
                SessionEntity(
                    activityId = activityId,
                    mode = SessionMode.MANUAL,
                    startedAt = 0,
                    endedAt = 60_000,
                    zoneId = "UTC",
                    workSeconds = 60,
                    createdAt = 0,
                    updatedAt = 0,
                ),
            )
        }
        assertEquals(2, repo.sessionCount(programming.id))
        repo.deleteActivity(programming.id)
        assertEquals(0, repo.sessionCount(programming.id))
        assertEquals(1, repo.sessionCount(reading.id))
    }
}
