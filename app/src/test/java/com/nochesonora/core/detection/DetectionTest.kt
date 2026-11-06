package com.nochesonora.core.detection

import com.nochesonora.core.audio.Pcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineTrackerTest {
    private val config = DetectorConfig()

    private fun calibrated(level: Float = -60f) = BaselineTracker(config).apply {
        repeat(config.calibrationBlocks) { onBlock(level + (it % 3) * 0.1f, track = true) }
    }

    @Test
    fun calibratesToP20() {
        val t = BaselineTracker(config)
        // 600 levels from −80 to −20 dB, shuffled.
        val levels = List(600) { -80f + it * 60f / 599 }.shuffled(kotlin.random.Random(3))
        levels.forEachIndexed { i, l ->
            assertFalse(t.isCalibrated)
            t.onBlock(l, track = true)
        }
        assertTrue(t.isCalibrated)
        assertEquals(-80f + 120 * 60f / 599, t.baselineDb!!, 0.01f)
        assertEquals(0.6f * 60f, t.variabilityDb!!, 0.2f)
        assertEquals(t.baselineDb!! + 10f, t.thresholdDb!!, 0.001f)
    }

    @Test
    fun provisionalBaselineAfterWarmup() {
        val t = BaselineTracker(config)
        repeat(config.warmupBlocks - 1) { t.onBlock(-50f, true) }
        assertNull("no baseline during warm-up", t.baselineDb)
        t.onBlock(-50f, true)
        assertEquals(-50f, t.baselineDb!!, 0.001f)
        assertFalse(t.isCalibrated)
        repeat(599 - config.warmupBlocks) { t.onBlock(-50f, true) }
        assertEquals(599f / 600f, t.calibrationProgress, 0.001f)
    }

    @Test
    fun eventBlocksDuringCalibrationDoNotRaiseBaseline() {
        val t = BaselineTracker(config)
        repeat(config.warmupBlocks) { t.onBlock(-60f, true) }
        // The rest of calibration is almost all speech (event blocks): the baseline must not rise.
        repeat(550) { t.onBlock(-25f, track = false) }
        repeat(600 - config.warmupBlocks - 550) { t.onBlock(-60f, true) }
        assertTrue(t.isCalibrated)
        assertEquals(-60f, t.baselineDb!!, 0.001f)
    }

    @Test
    fun oneSecondPeakDoesNotMoveBaseline() {
        val t = calibrated()
        val before = t.baselineDb!!
        repeat(10) { t.onBlock(-20f, track = true) }
        repeat(100) { t.onBlock(-60f, track = true) }
        assertEquals(before, t.baselineDb!!, 0.1f)
    }

    @Test
    fun sustainedStepOf15DbIsAbsorbedInAboutFourMinutes() {
        val t = calibrated()
        repeat(600) { t.onBlock(-45f, track = true) } // 1 min: still far off
        assertTrue("at 1 min it must not be absorbed yet: ${t.baselineDb}", t.baselineDb!! < -50f)
        repeat(1800) { t.onBlock(-45f, track = true) } // 4 min in total
        assertTrue("at 4 min it must be within 3 dB: ${t.baselineDb}", t.baselineDb!! > -48f)
    }

    @Test
    fun eventBlocksDoNotUpdate() {
        val t = calibrated()
        val before = t.baselineDb!!
        repeat(3000) { t.onBlock(-30f, track = false) }
        assertEquals(before, t.baselineDb!!, 0f)
    }
}

class EventDetectorTest {
    private val quiet = -60f
    private val loud = -40f

    /** Feeds 100 ms blocks; each sample carries its block number so trimming can be checked. */
    private class Driver(config: DetectorConfig = DetectorConfig()) {
        val detector = EventDetector(config)
        val events = ArrayList<ClosedEvent>()
        val t0 = 1_000_000L
        var block = 0
        val now: Long get() = t0 + block * 100L
        var sawEvent = false
        var lastOutput: DetectorOutput? = null

        fun feed(level: Float, blocks: Int = 1) = repeat(blocks) {
            val samples = ShortArray(1600) { block.toShort() }
            val out = detector.onBlock(now, samples, level)
            out.closedEvent?.let(events::add)
            if (out.state == DetectorState.EVENT) sawEvent = true
            lastOutput = out
            block++
        }

        fun calibrate(level: Float = -60f) = feed(level, 600)
    }

    @Test
    fun warmupNeverOpensEvents() {
        val d = Driver()
        d.feed(-10f, DetectorConfig().warmupBlocks)
        assertFalse(d.sawEvent)
        assertEquals(DetectorState.CALIBRATING, d.detector.state)
    }

    /** Regression: in a 1-minute test nothing was detected because calibration blocked detection. */
    @Test
    fun detectsEventsWhileStillCalibrating() {
        val d = Driver()
        d.feed(quiet, 100) // 10 s of silence
        d.feed(loud, 10) // 1 s of voice
        d.feed(quiet, 100)
        d.feed(loud, 5) // cough
        d.feed(quiet, 100)
        assertFalse("still calibrating (31.5 s)", d.detector.baseline.isCalibrated)
        assertEquals(2, d.events.size)
        assertEquals(quiet, d.detector.baseline.baselineDb!!, 0.001f)
    }

    @Test
    fun twoHundredMsDoNotTrigger() {
        val d = Driver().apply { calibrate() }
        d.feed(loud, 2)
        d.feed(quiet, 40)
        assertFalse(d.sawEvent)
        assertTrue(d.events.isEmpty())
    }

    @Test
    fun threeHundredMsTrigger() {
        val d = Driver().apply { calibrate() }
        val start = d.now
        d.feed(loud, 3)
        assertEquals(DetectorState.EVENT, d.detector.state)
        d.feed(quiet, 25)
        assertEquals(1, d.events.size)
        assertEquals(start, d.events[0].startedAtMs)
        assertEquals(start + 300, d.events[0].endedAtMs)
        assertEquals(DetectorState.LISTENING, d.detector.state)
    }

    @Test
    fun hangoverOf2400MsKeepsOneEvent() {
        val d = Driver().apply { calibrate() }
        d.feed(loud, 3)
        d.feed(quiet, 24)
        d.feed(loud, 3)
        d.feed(quiet, 25)
        assertEquals(1, d.events.size)
    }

    @Test
    fun gapOf2600MsSplitsInTwo() {
        val d = Driver().apply { calibrate() }
        d.feed(loud, 3)
        d.feed(quiet, 26)
        d.feed(loud, 3)
        d.feed(quiet, 25)
        assertEquals(2, d.events.size)
    }

    @Test
    fun exactPreRollAndPostRoll() {
        val d = Driver().apply { calibrate() }
        d.feed(quiet, 100)
        val onsetBlock = d.block
        val start = d.now
        d.feed(loud, 10)
        d.feed(quiet, 25)
        val e = d.events.single()
        assertEquals(start, e.startedAtMs)
        assertEquals(start + 1_000, e.endedAtMs)
        assertEquals(start - 3_000, e.clipStartedAtMs)
        assertEquals((30 + 10 + 20) * 1600, e.pcm.size)
        assertEquals((onsetBlock - 30).toShort(), e.pcm.first()) // 3 s before the onset
        assertEquals((onsetBlock + 10 + 19).toShort(), e.pcm.last()) // 2 s after the last active block
        assertFalse(e.wasCut)
    }

    @Test
    fun cutAtSixtySecondsWithoutRolls() {
        val d = Driver().apply { calibrate() }
        val start = d.now
        d.feed(loud, 650) // 65 s straight
        assertEquals(1, d.events.size)
        val first = d.events[0]
        assertTrue(first.wasCut)
        assertEquals(start, first.startedAtMs)
        assertEquals(start + 60_000, first.endedAtMs)
        assertEquals(Pcm.msToSamples(63_000), first.pcm.size) // 3 s of pre-roll + 60 s, no post-roll

        d.feed(quiet, 25)
        assertEquals(2, d.events.size)
        val second = d.events[1]
        assertEquals(first.endedAtMs, second.startedAtMs)
        assertEquals(second.startedAtMs, second.clipStartedAtMs) // no pre-roll
        assertEquals(start + 65_000, second.endedAtMs)
        assertEquals(Pcm.msToSamples(7_000), second.pcm.size) // 5 s + 2 s of post-roll
        assertEquals((600 + 600).toShort(), second.pcm.first()) // contiguous, no duplication
    }

    @Test
    fun continuationThatNeverRisesIsDropped() {
        val d = Driver().apply { calibrate() }
        d.feed(loud, 600)
        assertEquals(1, d.events.size)
        d.feed(quiet, 30)
        assertEquals(1, d.events.size)
        assertEquals(DetectorState.LISTENING, d.detector.state)
    }

    @Test
    fun interruptClosesOpenEvent() {
        val d = Driver().apply { calibrate() }
        d.feed(quiet, 50)
        d.feed(loud, 10)
        val e = d.detector.interrupt()
        assertNotNull(e)
        assertEquals((30 + 10) * 1600, e!!.pcm.size)
        assertEquals(DetectorState.LISTENING, d.detector.state)
        // After the pause there is no pre-roll from the earlier audio.
        d.feed(loud, 3)
        d.feed(quiet, 25)
        assertEquals(Pcm.msToSamples(2_300), d.events.last().pcm.size)
    }

    @Test
    fun interruptDuringOnsetDropsIt() {
        val d = Driver().apply { calibrate() }
        d.feed(loud, 2)
        assertNull(d.detector.interrupt())
    }

    @Test
    fun fanTurningOnIsAbsorbedWithoutAvalanche() {
        val d = Driver().apply { calibrate() }
        d.feed(-45f, 3_000) // fan +15 dB for 5 min
        assertTrue("events: ${d.events.size}", d.events.size <= 4)
        assertEquals(DetectorState.LISTENING, d.detector.state)
        assertTrue(d.detector.baseline.baselineDb!! > -55f)
        // And the night goes on: a real noise on top of the fan is detected.
        val before = d.events.size
        d.feed(-25f, 5)
        d.feed(-45f, 25)
        assertEquals(before + 1, d.events.size)
    }
}
