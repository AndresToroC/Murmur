package com.nochesonora.core.classification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnoreFilterTest {
    private fun result(category: Category, otherLabel: String?, vararg mean: Pair<String, Float>) = ClassificationResult(
        category = category,
        confidence = 0.6f,
        otherLabel = otherLabel,
        topLabels = mean.take(3).map { LabelScore(it.first, it.second) },
        meanScores = mean.map { LabelScore(it.first, it.second) },
        snore = SnoreResult(isPeriodic = false, isLowFrequency = false, lowFrequencyRatio = 0f, peakCount = 0),
        windowCount = 4,
    )

    private val dogAndFan = setOf(SoundType.DOG, SoundType.FAN)

    @Test
    fun ignoredSoundsAreSkipped() {
        assertEquals(SoundType.DOG, IgnoreFilter.ignoredType(result(Category.OTRO, "Bark", "Bark" to 0.6f), dogAndFan))
        assertEquals(SoundType.FAN, IgnoreFilter.ignoredType(result(Category.OTRO, "Air conditioning", "Air conditioning" to 0.5f), dogAndFan))
        // Otros sonidos, y nada si la lista está vacía.
        assertNull(IgnoreFilter.ignoredType(result(Category.OTRO, "Door", "Door" to 0.6f), dogAndFan))
        assertNull(IgnoreFilter.ignoredType(result(Category.OTRO, "Bark", "Bark" to 0.6f), emptySet()))
        // Solo clips OTRO: habla, ronquido o tos nunca se ignoran.
        assertNull(IgnoreFilter.ignoredType(result(Category.HABLA, null, "Speech" to 0.6f, "Bark" to 0.3f), dogAndFan))
    }

    @Test
    fun aRealSoundInsideKeepsTheClip() {
        // Ganó el perro, pero también se oyó habla: se guarda.
        assertNull(IgnoreFilter.ignoredType(result(Category.OTRO, "Bark", "Bark" to 0.5f, "Speech" to 0.08f), dogAndFan))
        assertNull(IgnoreFilter.ignoredType(result(Category.OTRO, "Bark", "Bark" to 0.5f, "Cough" to 0.05f), dogAndFan))
    }

    @Test
    fun voicesAndTvCanBeIgnoredDespiteSoundingLikeSpeech() {
        val tv = setOf(SoundType.MUSIC_TV)
        assertEquals(SoundType.MUSIC_TV, IgnoreFilter.ignoredType(result(Category.OTRO, "Television", "Television" to 0.5f, "Speech" to 0.2f), tv))
        // Pero el ronquido sigue protegiendo.
        assertNull(IgnoreFilter.ignoredType(result(Category.OTRO, "Television", "Television" to 0.5f, "Snoring" to 0.1f), tv))
    }

    @Test
    fun snoringAndCoughingAreNeverOfferedToIgnore() {
        assertFalse(IgnoreFilter.canIgnore(SoundType.SNORING))
        assertFalse(IgnoreFilter.canIgnore(SoundType.COUGH))
        assertFalse(IgnoreFilter.canIgnore(null))
        assertTrue(IgnoreFilter.canIgnore(SoundType.DOG))
        assertTrue(IgnoreFilter.canIgnore(SoundType.WIND))
    }
}
