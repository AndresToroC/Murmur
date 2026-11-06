package com.nochesonora

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nochesonora.audio.YamnetClassifier
import com.nochesonora.core.classification.Category
import com.nochesonora.core.classification.Windowing
import com.nochesonora.core.episodes.EpisodeLite
import com.nochesonora.data.db.AppDatabase
import com.nochesonora.data.db.EventEntity
import com.nochesonora.data.db.PauseEntity
import com.nochesonora.data.db.SessionEntity
import com.nochesonora.data.db.SessionStatus
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
class NightDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun event(sessionId: Long, category: Category, startS: Int, user: Category? = null) = EventEntity(
        sessionId = sessionId, episodeId = null, category = category, otherLabel = null,
        startedAt = startS * 1000L, endedAt = startS * 1000L + 5_000, peakDb = -20f, confidence = 0.5f,
        topLabelsJson = "[]", filePath = "", userCategory = user,
    )

    @Test
    fun deletingSessionCascades() = runTest {
        val dao = db.nightDao()
        val id = dao.insertSession(SessionEntity(startedAt = 0, endedAt = null, status = SessionStatus.EN_CURSO,
            baselineDbAtStart = null, lastHeartbeatAt = 0, sensitivityDb = 10, fullRecordingPath = null))
        val eventId = dao.insertEvent(event(id, Category.TOS, 10))
        dao.insertPause(PauseEntity(sessionId = id, startedAt = 1, endedAt = null, reason = "LLAMADA"))
        dao.replaceEpisodes(id, listOf(EpisodeLite(Category.TOS, 10_000, 15_000, listOf(eventId))))
        dao.deleteSession(id)
        assertNull(dao.getEvent(eventId))
        assertTrue(dao.getEvents(id).isEmpty())
        assertTrue(dao.observeEpisodes(id).first().isEmpty())
        assertTrue(dao.observePauses(id).first().isEmpty())
    }

    @Test
    fun summaryCountsEffectiveCategories() = runTest {
        val dao = db.nightDao()
        val id = dao.insertSession(SessionEntity(startedAt = 0, endedAt = 100_000, status = SessionStatus.COMPLETADA,
            baselineDbAtStart = -50f, lastHeartbeatAt = 0, sensitivityDb = 10, fullRecordingPath = null))
        dao.insertEvent(event(id, Category.RONQUIDO, 0))
        dao.insertEvent(event(id, Category.OTRO, 10, user = Category.HABLA))
        dao.insertEvent(event(id, Category.TOS, 20))
        dao.replaceEpisodes(id, listOf(EpisodeLite(Category.RONQUIDO, 0, 65_000, emptyList())))
        val row = dao.observeSummaries().first().single()
        assertEquals(1, row.snoreEvents)
        assertEquals(1, row.speechEvents)
        assertEquals(1, row.coughEvents)
        assertEquals(0, row.otherEvents)
        assertEquals(65_000L, row.snoreMs)
    }
}

@RunWith(AndroidJUnit4::class)
class YamnetClassifierTest {
    @Test
    fun loadsModelAndClassifiesSilence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YamnetClassifier.create(context).use { classifier ->
            val labels = classifier.classify(FloatArray(Windowing.WINDOW_SAMPLES))
            assertTrue("YAMNet no devolvió etiquetas", labels.isNotEmpty())
        }
    }
}
