package com.nochesonora.service

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nochesonora.audio.AudioSource
import com.nochesonora.core.SignalGen
import com.nochesonora.core.audio.Pcm
import com.nochesonora.core.audio.WavWriter
import com.nochesonora.core.classification.Category
import com.nochesonora.core.classification.LabelScore
import com.nochesonora.core.classification.SoundType
import com.nochesonora.core.classification.WindowClassifier
import com.nochesonora.core.detection.Sensitivity
import com.nochesonora.data.NightRepository
import com.nochesonora.data.db.AppDatabase
import com.nochesonora.data.files.SessionFileStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The full session as it runs on the phone — service engine → detector → classifier →
 * Room → WAV files — with recorded audio instead of the microphone and a fake YAMNet.
 * Reproduces the manual test that showed "Sin eventos · 0 clips" (plan §12).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SessionEngineTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: NightRepository
    private val liveState = LiveState()
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = NightRepository(db.nightDao(), SessionFileStore(context))
    }

    @After
    fun tearDown() = db.close()

    /** Plays back a PCM buffer in 100 ms blocks and asks the session to stop when it ends. */
    private class RecordedAudio(private val pcm: ShortArray, private val onEnd: () -> Unit) : AudioSource {
        private var pos = 0
        override var isOpen = false
        override val sourceName = "RECORDING"
        override val audioSessionId = 1
        override fun open(): Boolean { isOpen = true; return true }
        override fun release() { isOpen = false }
        override fun readBlock(buffer: ShortArray): Int {
            if (pos + buffer.size > pcm.size) {
                onEnd()
                SignalGen.whiteNoise(0.1, 40.0).copyInto(buffer)
                return buffer.size
            }
            System.arraycopy(pcm, pos, buffer, 0, buffer.size)
            pos += buffer.size
            return buffer.size
        }
    }

    /** Fake YAMNet: always "Silence"; the sound's label only in loud windows. */
    private fun fakeYamnet(labelAt: (rmsDb: Double) -> String?) = WindowClassifier { window ->
        val rms = sqrt(window.sumOf { (it * it).toDouble() } / window.size)
        val db = 20 * log10(rms + 1e-12)
        val label = labelAt(db)
        if (label != null) listOf(LabelScore(label, 0.6f), LabelScore("Silence", 0.3f)) else listOf(LabelScore("Silence", 0.75f))
    }

    private fun runSession(
        pcm: ShortArray,
        classifier: WindowClassifier,
        groupRepeats: Boolean = true,
        ignored: Set<SoundType> = emptySet(),
    ): Long = runBlocking {
        val session = repository.createSession(System.currentTimeMillis(), Sensitivity.MEDIUM.marginDb, saveFullRecording = false)
        lateinit var engine: SessionEngine
        engine = SessionEngine(
            context = context,
            repository = repository,
            liveState = liveState,
            onStatusChanged = { _, _ -> },
            openAudio = { RecordedAudio(pcm) { engine.requestStop() } },
            loadClassifier = { classifier },
        )
        engine.setIgnoredSounds(ignored)
        engine.run(session, Sensitivity.MEDIUM, groupRepeats)
        session.id
    }

    /** A room with background noise like the test phone's (baseline ≈ −47 dBFS). */
    private fun room(seconds: Double) = SignalGen.whiteNoise(seconds, amplitude = 250.0)

    @Test
    fun twoAndAHalfMinuteSessionSavesEverySoundWithItsClip() {
        val pcm = room(150.0)
        SignalGen.mixInto(pcm, SignalGen.voiceBurst(2.0, 8_000.0), 20.0) // speech during calibration
        SignalGen.mixInto(pcm, SignalGen.voiceBurst(3.0, 8_000.0), 70.0) // speech
        SignalGen.mixInto(pcm, SignalGen.whiteNoise(0.5, 9_000.0, seed = 5), 85.0) // cough
        SignalGen.mixInto(pcm, SignalGen.lowBurst(1.5, 9_000.0), 100.0) // snore
        SignalGen.mixInto(pcm, SignalGen.lowBurst(1.5, 9_000.0), 106.0)

        val sessionId = runSession(pcm, fakeYamnet { if (it > -35) "Speech" else null })

        val events = runBlocking { db.nightDao().getEvents(sessionId) }
        val t0 = runBlocking { db.nightDao().getSession(sessionId)!!.startedAt }
        val live = liveState.snapshot.value
        assertEquals(
            "events saved in Room: starts ${events.map { (it.startedAt - t0) / 1000.0 }} s; " +
                "discarded=${live.discardedCount} dropped by queue=${live.droppedEvents} errors=${live.saveErrors} " +
                "recent=${live.recent.map { "${(it.atMs - t0) / 1000.0}:${it.category}" }}",
            5,
            events.size,
        )
        assertTrue(events.all { it.category == Category.SPEECH })
        for (e in events) {
            val wav = File(e.filePath)
            assertTrue("missing WAV ${e.filePath}", wav.isFile)
            assertTrue(wav.length() > WavWriter.HEADER_SIZE + Pcm.msToSamples(5_000) * 2) // at least pre + post-roll
        }
        assertEquals(0, liveState.snapshot.value.saveErrors)
        assertEquals(5, liveState.snapshot.value.counts[Category.SPEECH])
        assertTrue("no temporary files",File(events[0].filePath).parentFile!!.list()!!.none { it.endsWith(".tmp") })
    }

    /**
     * Pause from the notification: releases the microphone, records the USER pause and, on
     * resume, reopens the microphone and keeps detecting with the same baseline.
     */
    @Test
    fun userPauseReleasesTheMicrophoneAndResumes() {
        val pcm = room(100.0)
        SignalGen.mixInto(pcm, SignalGen.voiceBurst(3.0, 8_000.0), 80.0) // speech after resuming
        var opens = 0
        var releasesWhilePaused = 0
        val session = runBlocking { repository.createSession(System.currentTimeMillis(), Sensitivity.MEDIUM.marginDb, saveFullRecording = false) }
        lateinit var engine: SessionEngine
        val audio = object : AudioSource {
            private var pos = 0
            private var paused = false
            private var inPause = false
            override var isOpen = false
            override val sourceName = "RECORDING"
            override val audioSessionId = 1
            override fun open(): Boolean { opens++; inPause = false; isOpen = true; return true }
            override fun release() { if (inPause) releasesWhilePaused++; isOpen = false }
            override fun readBlock(buffer: ShortArray): Int {
                if (pos == Pcm.msToSamples(70_000).toInt() && !paused) {
                    // At 70 s the user pauses; 600 ms later they tap "Resume".
                    paused = true
                    inPause = true
                    engine.setUserPaused(true)
                    Thread {
                        Thread.sleep(600)
                        engine.setUserPaused(false)
                    }.start()
                }
                if (pos + buffer.size > pcm.size) {
                    engine.requestStop()
                    SignalGen.whiteNoise(0.1, 40.0).copyInto(buffer)
                    return buffer.size
                }
                System.arraycopy(pcm, pos, buffer, 0, buffer.size)
                pos += buffer.size
                return buffer.size
            }
        }
        engine = SessionEngine(
            context = context,
            repository = repository,
            liveState = liveState,
            onStatusChanged = { _, _ -> },
            openAudio = { audio },
            loadClassifier = { fakeYamnet { if (it > -35) "Speech" else null } },
        )
        runBlocking { engine.run(session, Sensitivity.MEDIUM) }

        val pauses = runBlocking { db.nightDao().observePauses(session.id).first() }
        assertEquals(listOf(com.nochesonora.data.db.PauseReason.USER), pauses.map { it.reason })
        assertTrue("the pause was closed", pauses.single().endedAt != null)
        assertEquals("the microphone was released during the pause", 1, releasesWhilePaused)
        assertEquals("it was reopened on resume", 2, opens)
        assertEquals(1, runBlocking { db.nightDao().getEvents(session.id) }.size)
        assertEquals(LivePhase.LISTENING, liveState.snapshot.value.phase)
    }

    @Test
    fun backgroundOnlySessionSavesNothing() {
        val pcm = room(90.0)
        SignalGen.mixInto(pcm, SignalGen.whiteNoise(2.0, 4_000.0, seed = 9), 70.0) // brief background noise
        val sessionId = runSession(pcm, fakeYamnet { null })
        assertEquals(0, runBlocking { db.nightDao().getEvents(sessionId) }.size)
        assertEquals(1, liveState.snapshot.value.discardedCount)
        assertEquals(0, liveState.snapshot.value.saveErrors)
    }

    /** Air from a fan blowing on the microphone: gusts that YAMNet hears as wind. */
    private fun windyNight(): ShortArray {
        val pcm = room(150.0)
        listOf(70.0, 85.0, 100.0, 115.0).forEachIndexed { i, at ->
            SignalGen.mixInto(pcm, SignalGen.whiteNoise(1.5, 9_000.0, seed = 20 + i), at)
        }
        return pcm
    }

    /** Wind is heard throughout the clip (louder in the gust), as with a fan nearby. */
    private val windYamnet = WindowClassifier { window ->
        val rms = sqrt(window.sumOf { (it * it).toDouble() } / window.size)
        val loud = 20 * log10(rms + 1e-12) > -35
        listOf(LabelScore("Wind noise (microphone)", if (loud) 0.6f else 0.35f), LabelScore("Silence", 0.2f))
    }

    @Test
    fun repeatedWindIsSavedOnceAndCounted() {
        val sessionId = runSession(windyNight(), windYamnet)
        val events = runBlocking { db.nightDao().getEvents(sessionId) }
        assertEquals(1, events.size)
        assertEquals(Category.OTHER, events.single().category)
        assertEquals(3, events.single().repeats)
        assertTrue(events.single().lastRepeatAt!! > events.single().startedAt)
        val live = liveState.snapshot.value
        assertEquals(3, live.repeatedCount)
        assertEquals(1, live.counts[Category.OTHER])
        assertEquals(0, live.saveErrors)
        // Only one WAV on disk.
        assertEquals(1, File(events.single().filePath).parentFile!!.list()!!.count { it.endsWith(".wav") })
    }

    @Test
    fun repeatedWindIsKeptWhenGroupingIsOff() {
        val sessionId = runSession(windyNight(), windYamnet, groupRepeats = false)
        val events = runBlocking { db.nightDao().getEvents(sessionId) }
        assertEquals(4, events.size)
        assertTrue(events.all { it.repeats == 0 })
    }

    @Test
    fun ignoredSoundIsNotSaved() {
        val sessionId = runSession(windyNight(), windYamnet, ignored = setOf(SoundType.WIND))
        val events = runBlocking { db.nightDao().getEvents(sessionId) }
        assertEquals(0, events.size)
        val live = liveState.snapshot.value
        assertEquals(4, live.ignoredCount)
        assertEquals(0, live.repeatedCount)
        assertEquals(0, live.saveErrors)
    }

    @Test
    fun ignoringAnotherSoundKeepsTheWind() {
        val sessionId = runSession(windyNight(), windYamnet, groupRepeats = false, ignored = setOf(SoundType.DOG))
        assertEquals(4, runBlocking { db.nightDao().getEvents(sessionId) }.size)
    }

    @Test
    fun savedClipsOfASoundCanBeDeletedTogether() {
        val sessionId = runSession(windyNight(), windYamnet, groupRepeats = false)
        runBlocking {
            val wind = repository.clipsOfType(SoundType.WIND)
            assertEquals(4, wind.size)
            assertEquals(0, repository.clipsOfType(SoundType.DOG).size)
            val dir = File(wind.first().filePath).parentFile!!
            repository.deleteEvents(wind)
            assertEquals(0, db.nightDao().getEvents(sessionId).size)
            assertEquals(0, dir.list()!!.count { it.endsWith(".wav") })
        }
    }
}
