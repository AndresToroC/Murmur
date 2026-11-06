package com.nochesonora.core.classification

import com.nochesonora.core.SignalGen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowingTest {
    @Test
    fun windowCounts() {
        assertEquals(1, Windowing.windowCount(0))
        assertEquals(1, Windowing.windowCount(8_000))
        assertEquals(1, Windowing.windowCount(15_600))
        assertEquals(2, Windowing.windowCount(15_601))
        assertEquals(2, Windowing.windowCount(23_400))
        assertEquals(3, Windowing.windowCount(23_401))
        assertEquals(133, Windowing.windowCount(65 * 16_000))
    }

    @Test
    fun windowsOverlapByHalf() {
        val pcm = ShortArray(40_000) { (it % 30_000).toShort() }
        val out = FloatArray(Windowing.WINDOW_SAMPLES)
        Windowing.fillWindow(pcm, 1, out)
        assertEquals(7_800 / 32768f, out[0], 1e-6f)
        assertEquals(15_599 / 32768f, out[7_799], 1e-6f) // middle of window 1 = second half of window 0
    }

    @Test
    fun lastWindowIsZeroPadded() {
        val pcm = ShortArray(20_000) { 1000 }
        val out = FloatArray(Windowing.WINDOW_SAMPLES)
        Windowing.fillWindow(pcm, 1, out) // starts at 7,800, 12,200 samples remain
        assertEquals(1000 / 32768f, out[12_199], 1e-6f)
        assertEquals(0f, out[12_200], 0f)
        assertEquals(0f, out.last(), 0f)
    }
}

class LabelMapperTest {
    @Test
    fun mapsAllListedLabels() {
        listOf("Snoring", "Gasp").forEach { assertEquals(LabelKind.SNORE, LabelMapper.kind(it)) }
        assertEquals(LabelKind.BREATHING, LabelMapper.kind("Breathing"))
        listOf("Speech", "Conversation", "Whispering", "Babbling", "Narration, monologue", "Shout", "Laughter")
            .forEach { assertEquals(it, LabelKind.SPEECH, LabelMapper.kind(it)) }
        listOf("Cough", "Sneeze", "Throat clearing").forEach { assertEquals(LabelKind.COUGH, LabelMapper.kind(it)) }
        listOf("Mechanical fan", "Air conditioning", "Hum", "White noise", "Pink noise", "Silence", "Static")
            .forEach { assertEquals(it, LabelKind.BACKGROUND, LabelMapper.kind(it)) }
        listOf("Engine", "Idling", "Engine starting").forEach { assertEquals(LabelKind.ENGINE, LabelMapper.kind(it)) }
        assertEquals(LabelKind.OTHER, LabelMapper.kind("Dog"))
        assertEquals(LabelKind.OTHER, LabelMapper.kind("snoring")) // exact match
    }
}

class VotingClassifierTest {
    private val notSnore = SnoreResult(isPeriodic = false, isLowFrequency = false, lowFrequencyRatio = 0f, peakCount = 0)
    private val periodicOnly = notSnore.copy(isPeriodic = true)
    private val snoreLike = SnoreResult(isPeriodic = true, isLowFrequency = true, lowFrequencyRatio = 0.9f, peakCount = 5)

    private fun w(vararg pairs: Pair<String, Float>) = pairs.map { LabelScore(it.first, it.second) }

    @Test
    fun weightedVote() {
        val r = VotingClassifier.classify(
            listOf(w("Speech" to 0.6f, "Snoring" to 0.35f), w("Speech" to 0.7f), w("Snoring" to 0.4f)),
            notSnore,
        )
        assertEquals(Category.HABLA, r.category)
        assertEquals(1.3f / 3, r.confidence, 1e-4f)
        assertNull(r.otherLabel)
    }

    @Test
    fun scoresBelowThresholdDoNotVote() {
        val r = VotingClassifier.classify(listOf(w("Cough" to 0.29f), w("Cough" to 0.29f)), notSnore)
        assertEquals(Category.OTRO, r.category)
        assertEquals(LabelMapper.UNKNOWN_LABEL, r.otherLabel)
        assertEquals(0f, r.confidence, 0f)
        assertEquals("Cough", r.topLabels.first().label) // the top 3 is saved anyway
    }

    @Test
    fun noWindowsIsUnknown() {
        val r = VotingClassifier.classify(listOf(emptyList(), emptyList()), notSnore)
        assertEquals(Category.OTRO, r.category)
        assertEquals(LabelMapper.UNKNOWN_LABEL, r.otherLabel)
    }

    @Test
    fun backgroundWins() {
        val r = VotingClassifier.classify(List(5) { w("Mechanical fan" to 0.8f, "Speech" to 0.1f) }, notSnore)
        assertEquals(Category.FONDO, r.category)
    }

    /**
     * Regression using what YAMNet actually returns for a cough clip (ESC-50): "Silence" in almost
     * every window because of the pre/post-roll, and "Cough" in one or two. FONDO used to win and the clip was discarded.
     */
    @Test
    fun shortCoughAmongSilenceIsCough() {
        val windows = List(10) { if (it == 4 || it == 5) w("Cough" to 0.55f, "Silence" to 0.2f) else w("Silence" to 0.7f) }
        assertEquals(Category.TOS, VotingClassifier.classify(windows, notSnore).category)
    }

    @Test
    fun speechOverFanIsSpeech() {
        val windows = List(8) { if (it < 3) w("Speech" to 0.4f, "Mechanical fan" to 0.6f) else w("Mechanical fan" to 0.8f) }
        assertEquals(Category.HABLA, VotingClassifier.classify(windows, notSnore).category)
    }

    @Test
    fun backgroundStillBeatsWeakOther() {
        val windows = List(6) { if (it == 0) w("Inside, small room" to 0.35f, "Hum" to 0.5f) else w("Hum" to 0.7f) }
        assertEquals(Category.FONDO, VotingClassifier.classify(windows, notSnore).category)
    }

    @Test
    fun sustainedEngineIsBackground() {
        val windows = List(10) { if (it < 7) w("Engine" to 0.5f) else w("Idling" to 0.4f) }
        assertEquals(Category.FONDO, VotingClassifier.classify(windows, notSnore).category)
    }

    @Test
    fun briefEngineIsOther() {
        val windows = List(10) { if (it < 2) w("Engine" to 0.5f) else emptyList() }
        val r = VotingClassifier.classify(windows, notSnore)
        assertEquals(Category.OTRO, r.category)
        assertEquals("Engine", r.otherLabel)
    }

    @Test
    fun breathingDependsOnPeriodicity() {
        val windows = List(4) { w("Breathing" to 0.5f) }
        assertEquals(Category.RONQUIDO, VotingClassifier.classify(windows, periodicOnly).category)
        val r = VotingClassifier.classify(windows, notSnore)
        assertEquals(Category.OTRO, r.category)
        assertEquals("Breathing", r.otherLabel)
    }

    @Test
    fun snoreHeuristicBoostsEvenWithoutSnoringLabel() {
        val windows = List(4) { w("Dog" to 0.2f) }
        val r = VotingClassifier.classify(windows, snoreLike)
        assertEquals(Category.RONQUIDO, r.category)
        assertEquals(0.3f, r.confidence, 1e-4f)
    }

    @Test
    fun otherKeepsHeaviestOriginalLabel() {
        val windows = listOf(w("Dog" to 0.4f, "Door" to 0.35f), w("Door" to 0.5f), w("Door" to 0.31f))
        val r = VotingClassifier.classify(windows, notSnore)
        assertEquals(Category.OTRO, r.category)
        assertEquals("Door", r.otherLabel)
    }

    @Test
    fun topThreeByMeanScore() {
        val windows = listOf(
            w("Snoring" to 0.9f, "Speech" to 0.2f, "Dog" to 0.1f, "Hum" to 0.05f),
            w("Snoring" to 0.7f, "Hum" to 0.4f),
        )
        val r = VotingClassifier.classify(windows, notSnore)
        assertEquals(listOf("Snoring", "Hum", "Speech"), r.topLabels.map { it.label })
        assertEquals(0.8f, r.topLabels[0].score, 1e-4f)
        assertEquals(0.225f, r.topLabels[1].score, 1e-4f)
        assertEquals(4, r.meanScores.size)
    }

    @Test
    fun jsonRoundTrip() {
        val labels = listOf(LabelScore("Snoring", 0.62f), LabelScore("Narration, monologue", 0.1f), LabelScore("A \"q\"", 0.05f))
        val json = VotingClassifier.toJson(labels)
        assertTrue(json.startsWith("""[{"label":"Snoring","score":0.620},{"""))
        assertEquals(labels, VotingClassifier.fromJson(json))
    }
}

class SnoreHeuristicTest {
    private fun background(seconds: Double) = SignalGen.whiteNoise(seconds, 30.0)

    @Test
    fun lowPulsesEvery3_5sAreSnoreLike() {
        val pcm = background(21.0)
        listOf(0.5, 4.0, 7.5, 11.0, 14.5, 18.0).forEach { SignalGen.mixInto(pcm, SignalGen.lowBurst(1.0, 8000.0), it) }
        val r = SnoreHeuristic.analyze(pcm)
        assertEquals(6, r.peakCount)
        assertTrue(r.isPeriodic)
        assertTrue("ratio ${r.lowFrequencyRatio}", r.isLowFrequency)
        assertTrue(r.isSnoreLike)
    }

    @Test
    fun aperiodicVoiceIsNot() {
        val pcm = background(16.0)
        // Isolated phrases at irregular intervals (1.9 s, 7 s, 2.6 s...).
        listOf(0.5 to 0.5, 2.4 to 0.7, 9.4 to 0.4, 12.0 to 0.6, 14.6 to 0.5)
            .forEach { (at, len) -> SignalGen.mixInto(pcm, SignalGen.voiceBurst(len, 8000.0), at) }
        val r = SnoreHeuristic.analyze(pcm)
        assertFalse(r.isPeriodic)
        assertFalse("ratio ${r.lowFrequencyRatio}", r.isLowFrequency)
        assertFalse(r.isSnoreLike)
    }

    @Test
    fun pulsesEverySecondAreNot() {
        val pcm = background(20.0)
        for (i in 0 until 20) SignalGen.mixInto(pcm, SignalGen.lowBurst(0.5, 8000.0), i.toDouble())
        val r = SnoreHeuristic.analyze(pcm)
        assertTrue(r.isLowFrequency)
        assertFalse(r.isPeriodic)
        assertFalse(r.isSnoreLike)
    }

    @Test
    fun shortClipIsNotPeriodic() {
        val r = SnoreHeuristic.analyze(SignalGen.lowBurst(1.0, 8000.0))
        assertFalse(r.isPeriodic)
    }

    @Test
    fun lowPassSeparatesBands() {
        assertTrue(SnoreHeuristic.lowFrequencyRatio(SignalGen.sine(150.0, 1.0, 10000.0), 16_000) > 0.9f)
        assertTrue(SnoreHeuristic.lowFrequencyRatio(SignalGen.sine(3000.0, 1.0, 10000.0), 16_000) < 0.05f)
    }
}

class ClipAnalyzerTest {
    @Test
    fun classifiesEveryWindow() {
        var calls = 0
        val analyzer = ClipAnalyzer { window ->
            calls++
            assertEquals(Windowing.WINDOW_SAMPLES, window.size)
            listOf(LabelScore("Cough", 0.8f))
        }
        val r = analyzer.analyze(ShortArray(16_000 * 5))
        assertEquals(Windowing.windowCount(80_000), calls)
        assertEquals(calls, r.windowCount)
        assertEquals(Category.TOS, r.category)
        assertEquals(0.8f, r.confidence, 1e-4f)
    }
}
