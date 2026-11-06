package com.nochesonora.transcription

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class SpeechPrepTest {
    private val rate = SpeechPrep.SAMPLE_RATE

    /** Test clip: soft background noise with "voice" (a tone) on top in the given stretches (in ms). */
    private fun clip(totalMs: Int, voice: List<IntRange>, amplitude: Double = 200.0): ShortArray {
        val rnd = Random(7)
        return ShortArray(totalMs * rate / 1000) { i ->
            val ms = i * 1000 / rate
            val v = if (voice.any { ms in it }) amplitude * sin(2 * PI * 300 * i / rate) else 0.0
            (v + rnd.nextInt(-20, 21)).toInt().toShort()
        }
    }

    private fun rms(x: ShortArray, from: Int, to: Int): Double =
        sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))

    private fun ms(samples: Int) = samples * 1000 / rate

    @Test
    fun quietSpeechAfterPreRollIsTrimmedAndAmplified() {
        // 3 s of pre-roll, 1.2 s of quiet voice (-44 dBFS), 2 s of silence.
        val phrases = SpeechPrep.phrases(clip(6_200, listOf(3_000 until 4_200)))
        assertEquals(1, phrases.size)
        val p = phrases.single()
        // Without the 3 s of pre-roll: voice + padding (≈0.65 s) + added silences (1.5 s).
        assertTrue("lasts ${ms(p.size)} ms", ms(p.size) in 3_000..3_600)
        // The voice is raised to conversational level (≈ -20 dBFS = 3277).
        val center = 300 * rate / 1000 + 250 * rate / 1000 + 600 * rate / 1000
        val level = rms(p, center - 1_600, center + 1_600)
        assertTrue("level $level", level in 2_500.0..4_000.0)
    }

    @Test
    fun longPauseSplitsShortPauseDoesNot() {
        val split = SpeechPrep.phrases(clip(8_000, listOf(1_000 until 2_000, 3_500 until 4_500)))
        assertEquals(2, split.size)
        val joined = SpeechPrep.phrases(clip(8_000, listOf(1_000 until 2_000, 2_300 until 3_300)))
        assertEquals(1, joined.size)
    }

    @Test
    fun aClickAloneIsDroppedWhenThereIsRealSpeech() {
        // A 60 ms knock and, far away, a phrase.
        val phrases = SpeechPrep.phrases(clip(9_000, listOf(1_000 until 1_060, 5_000 until 6_500)))
        assertEquals(1, phrases.size)
    }

    @Test
    fun silenceOnlyFallsBackToTheWholeClip() {
        val input = clip(4_000, emptyList())
        val phrases = SpeechPrep.phrases(input)
        assertEquals(1, phrases.size)
        assertTrue(phrases.single().size >= input.size)
        assertEquals(emptyList<ShortArray>(), SpeechPrep.phrases(ShortArray(0)))
    }

    @Test
    fun longSpeechIsSplitAndPhraseCountIsCapped() {
        val long = SpeechPrep.phrases(clip(50_000, listOf(0 until 50_000)))
        assertTrue("${long.size} phrases", long.size >= 3)
        long.forEach { assertTrue(ms(it.size) <= 20_000 + 2_200) }

        val many = (0 until 30).map { k -> (k * 1_500) until (k * 1_500 + 600) }
        val capped = SpeechPrep.phrases(clip(46_000, many))
        assertTrue("${capped.size} phrases", capped.size in 1..SpeechPrep.MAX_PHRASES)
    }

    @Test
    fun limiterNeverClipsAndLeavesNormalLevelsAlone() {
        assertEquals(1_000.toShort(), SpeechPrep.limit(1_000.0))
        assertEquals((-1_000).toShort(), SpeechPrep.limit(-1_000.0))
        assertTrue(SpeechPrep.limit(1e6) in 30_000..32_767)
        assertTrue(SpeechPrep.limit(-1e6) in -32_767..-30_000)
        // An already loud voice is neither lowered nor clipped.
        val loud = SpeechPrep.phrases(clip(4_000, listOf(1_000 until 3_000), amplitude = 20_000.0)).single()
        assertTrue(loud.maxOf { abs(it.toInt()) } <= 32_767)
        assertTrue(loud.maxOf { abs(it.toInt()) } > 15_000)
    }

    @Test
    fun bytesRoundTrip() {
        val s = shortArrayOf(0, 1, -1, 32_767, -32_768, 1_234)
        assertArrayEquals(s, SpeechPrep.toSamples(SpeechPrep.toBytes(s)))
    }
}
