package com.aleksalatalo.logger.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aleksalatalo.logger.data.db.AppDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real 1 -> 2 auto migration. room-testing's MigrationTestHelper cannot be used: it needs
 * kotlinx-serialization 1.8, while navigation-compose pins 1.7.3 and AGP forces the test classpath to match.
 * So the v1 database is built from the exported schema 1.json (an androidTest asset provided by the
 * Room Gradle plugin), then opened through Room, which migrates and validates it against version 2.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    private fun createV1Database() {
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("${AppDatabase::class.java.name}/1.json").bufferedReader().use { JSONObject(it.readText()) }
            .getJSONObject("database")
        context.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        db.use {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                it.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) {
                    it.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) it.execSQL(setup.getString(i))
            it.execSQL("INSERT INTO activity_group (id, name, sortOrder) VALUES (1, 'Sport', 0)")
            it.execSQL(
                "INSERT INTO activity (id, name, groupId, color, archived, sortOrder, createdAt) " +
                    "VALUES (1, 'Planche', 1, 0, 0, 0, 100)",
            )
            it.version = 1
        }
    }

    @Test
    fun migrate1To2AddsLeisureColumns() {
        createV1Database()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val db = room.openHelper.writableDatabase // opens, migrates and validates
            assertEquals(2, db.version)
            db.query("SELECT leisure FROM activity_group WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            db.query("SELECT leisure FROM activity WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
            }
        } finally {
            room.close()
        }
    }
}
