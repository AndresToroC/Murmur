package com.nochesonora.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nochesonora.core.classification.Category
import com.nochesonora.core.classification.LabelMapper
import com.nochesonora.core.detection.Sensitivity
import com.nochesonora.data.db.AppDatabase
import com.nochesonora.data.db.PauseReason
import com.nochesonora.data.db.SessionStatus
import com.nochesonora.data.settings.storedRetention
import com.nochesonora.data.settings.storedSensitivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Nights recorded before 0.8.0 stored Spanish values; migration 3 → 4 rewrites them in English. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class EnglishValuesMigrationTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun spanishValuesBecomeEnglish() = runBlocking {
        val sql = db.openHelper.writableDatabase
        // Rows as version 3 wrote them.
        sql.execSQL(
            "INSERT INTO sessions (id, startedAt, endedAt, status, baselineDbAtStart, lastHeartbeatAt, sensitivityDb, fullRecordingPath, isFavorite, notes) " +
                "VALUES (1, 1000, 9000, 'INTERRUMPIDA', -52.0, 9000, 10, NULL, 0, NULL)",
        )
        val events = listOf(
            Triple(1, "'RONQUIDO'", "NULL"), Triple(2, "'HABLA'", "'TOS'"), Triple(3, "'OTRO'", "NULL"),
            Triple(4, "'OTRO'", "NULL"), Triple(5, "'OTRO'", "'HABLA'"),
        )
        val labels = mapOf(3 to "'Desconocido'", 4 to "'Sin clasificar'", 5 to "'Dog'")
        for ((id, category, user) in events) {
            sql.execSQL(
                "INSERT INTO events (id, sessionId, episodeId, category, otherLabel, startedAt, endedAt, peakDb, confidence, topLabelsJson, filePath, userCategory, transcript, repeats, lastRepeatAt) " +
                    "VALUES ($id, 1, NULL, $category, ${labels[id] ?: "NULL"}, ${id * 1000}, ${id * 1000 + 500}, -40.0, 0.5, '[]', '', $user, NULL, 0, NULL)",
            )
        }
        sql.execSQL("INSERT INTO episodes (id, sessionId, category, startedAt, endedAt, eventCount) VALUES (1, 1, 'RONQUIDO', 1000, 1500, 1)")
        sql.execSQL("INSERT INTO pauses (id, sessionId, startedAt, endedAt, reason) VALUES (1, 1, 2000, 3000, 'MICROFONO_OCUPADO')")
        sql.execSQL("INSERT INTO pauses (id, sessionId, startedAt, endedAt, reason) VALUES (2, 1, 4000, 5000, 'USUARIO')")

        AppDatabase.MIGRATION_3_4.migrate(sql)

        val dao = db.nightDao()
        assertEquals(SessionStatus.INTERRUPTED, dao.getSession(1)!!.status)
        val saved = dao.getEvents(1).associateBy { it.id }
        assertEquals(Category.SNORING, saved.getValue(1).category)
        assertNull(saved.getValue(1).userCategory)
        assertEquals(Category.SPEECH, saved.getValue(2).category)
        assertEquals(Category.COUGH, saved.getValue(2).userCategory)
        assertEquals(Category.OTHER, saved.getValue(3).category)
        assertEquals(LabelMapper.UNKNOWN_LABEL, saved.getValue(3).otherLabel)
        assertEquals(LabelMapper.UNCLASSIFIED_LABEL, saved.getValue(4).otherLabel)
        assertEquals("Dog", saved.getValue(5).otherLabel)
        assertEquals(Category.SPEECH, saved.getValue(5).userCategory)
        assertEquals(Category.SNORING, dao.observeEpisodes(1).first().single().category)
        assertEquals(listOf(PauseReason.MIC_BUSY, PauseReason.USER), dao.observePauses(1).first().map { it.reason })
        // The history summary counts by the English names.
        val row = dao.observeSummaries().first().single()
        assertEquals(1, row.snoreEvents)
        assertEquals(1, row.speechEvents)
        assertEquals(2, row.otherEvents)
        assertEquals(1, row.coughEvents)
    }

    @Test
    fun settingsSavedWithSpanishNamesAreStillRead() {
        assertEquals(Sensitivity.LOW, storedSensitivity("BAJA"))
        assertEquals(Sensitivity.MEDIUM, storedSensitivity("MEDIA"))
        assertEquals(Sensitivity.HIGH, storedSensitivity("HIGH"))
        assertEquals(Retention.FOREVER, storedRetention("SIEMPRE"))
        assertEquals(Retention.DAYS_7, storedRetention("DIAS_7"))
        assertEquals(Retention.DAYS_30, storedRetention("DAYS_30"))
        assertNull(storedSensitivity("nonsense"))
    }
}
