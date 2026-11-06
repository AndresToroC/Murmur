package com.nochesonora.core

import com.nochesonora.core.audio.Pcm
import com.nochesonora.core.classification.Category
import com.nochesonora.core.classification.ClipAnalyzer
import com.nochesonora.core.classification.LabelScore
import com.nochesonora.core.classification.WindowClassifier
import com.nochesonora.core.detection.ClosedEvent
import com.nochesonora.core.detection.EventDetector
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * End-to-end 1-minute session (layers 1–3), like the manual test that gave "0 events":
 * quiet room, speech at 12 s, cough at 30 s, speech at 45 s, and it stops at 60 s.
 */
class SessionSimulationTest {
    /** Mimics YAMNet: always "Silence", and the sound's label only in loud windows. */
    private fun fakeYamnet(loudLabel: String) = WindowClassifier { window ->
        val rms = kotlin.math.sqrt(window.sumOf { (it * it).toDouble() } / window.size)
        val db = 20 * kotlin.math.log10(rms + 1e-12)
        if (db > -40) listOf(LabelScore(loudLabel, 0.6f), LabelScore("Silence", 0.3f)) else listOf(LabelScore("Silence", 0.75f))
    }

    private fun runSession(pcm: ShortArray): List<ClosedEvent> {
        val detector = EventDetector()
        val events = ArrayList<ClosedEvent>()
        val block = 1_600
        var i = 0
        while (i + block <= pcm.size) {
            detector.onBlock(i * 1000L / Pcm.SAMPLE_RATE, pcm, offset = i, length = block, levelDb = Pcm.rmsDb(pcm, i, block))
                .closedEvent?.let(events::add)
            i += block
        }
        detector.interrupt()?.let(events::add) // "Stop"
        return events
    }

    private fun oneMinute(): ShortArray {
        val pcm = SignalGen.whiteNoise(60.0, amplitude = 40.0) // ≈ −63 dBFS
        SignalGen.mixInto(pcm, SignalGen.voiceBurst(1.5, 6_000.0), 12.0)
        SignalGen.mixInto(pcm, SignalGen.whiteNoise(0.5, 6_000.0, seed = 7), 30.0)
        SignalGen.mixInto(pcm, SignalGen.voiceBurst(2.0, 6_000.0), 45.0)
        return pcm
    }

    @Test
    fun oneMinuteSessionDetectsEverySound() {
        val events = runSession(oneMinute())
        assertEquals(3, events.size)
    }

    @Test
    fun shortSoundsAreNotDiscardedAsBackground() {
        val analyzer = ClipAnalyzer(fakeYamnet("Cough"))
        val categories = runSession(oneMinute()).map { analyzer.analyze(it.pcm).category }
        assertEquals(List(3) { Category.COUGH }, categories)
    }
}
