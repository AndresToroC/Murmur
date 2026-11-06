package com.nochesonora.core.classification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepeatFilterTest {
    private fun result(category: Category, otherLabel: String?, vararg mean: Pair<String, Float>) = ClassificationResult(
        category = category,
        confidence = 0.6f,
        otherLabel = otherLabel,
        topLabels = emptyList(),
        meanScores = mean.map { LabelScore(it.first, it.second) },
        snore = SnoreResult(isPeriodic = false, isLowFrequency = false, lowFrequencyRatio = 0f, peakCount = 0),
        windowCount = 4,
    )

    @Test
    fun ambientNoisesAreRecognized() {
        assertEquals(SoundType.WIND, RepeatFilter.ambientType(result(Category.OTRO, "Wind noise (microphone)", "Wind noise (microphone)" to 0.6f)))
        assertEquals(SoundType.WIND, RepeatFilter.ambientType(result(Category.OTRO, "Wind")))
        assertEquals(SoundType.RAIN, RepeatFilter.ambientType(result(Category.OTRO, "Rain on surface")))
        assertEquals(SoundType.ROOM, RepeatFilter.ambientType(result(Category.OTRO, "Inside, small room")))
        // Sounds that matter, or that are not ambient, are never folded.
        assertNull(RepeatFilter.ambientType(result(Category.OTRO, "Dog")))
        assertNull(RepeatFilter.ambientType(result(Category.OTRO, "Door")))
        assertNull(RepeatFilter.ambientType(result(Category.HABLA, null, "Speech" to 0.5f)))
        assertNull(RepeatFilter.ambientType(result(Category.RONQUIDO, null)))
        assertNull(RepeatFilter.ambientType(result(Category.OTRO, LabelMapper.UNKNOWN_LABEL)))
    }

    @Test
    fun windWithSpeechInsideIsNotFolded() {
        // Wind won, but speech was heard in one window: the clip is saved.
        assertNull(
            RepeatFilter.ambientType(result(Category.OTRO, "Wind", "Wind" to 0.5f, "Speech" to 0.08f)),
        )
        assertNull(RepeatFilter.ambientType(result(Category.OTRO, "Wind", "Snoring" to 0.05f)))
        assertNull(RepeatFilter.ambientType(result(Category.OTRO, "Wind", "Cough" to 0.04f)))
        // A tiny trace does not count.
        assertEquals(SoundType.WIND, RepeatFilter.ambientType(result(Category.OTRO, "Wind", "Speech" to 0.02f)))
    }

    @Test
    fun foldsIntoTheFirstWhileItKeepsComing() {
        val f = RepeatFilter(windowMs = 60_000)
        assertNull(f.foldInto(SoundType.WIND, 0))
        f.saved(SoundType.WIND, eventId = 7, atMs = 0)
        assertEquals(7L, f.foldInto(SoundType.WIND, 50_000))
        // The window counts from the last time it was heard, not from the first one.
        assertEquals(7L, f.foldInto(SoundType.WIND, 100_000))
        // A different type is tracked on its own.
        assertNull(f.foldInto(SoundType.RAIN, 100_000))
        assertNull(f.foldInto(null, 100_000))
        // More than a minute without wind: it is saved again.
        assertNull(f.foldInto(SoundType.WIND, 170_001))
        f.saved(SoundType.WIND, eventId = 9, atMs = 170_001)
        assertEquals(9L, f.foldInto(SoundType.WIND, 180_000))
    }
}
