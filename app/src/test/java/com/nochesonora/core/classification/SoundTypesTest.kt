package com.nochesonora.core.classification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class SoundTypesTest {
    /** The 521 real labels: MediaPipe's .tflite carries `yamnet_label_list.txt` attached as a zip. */
    private val yamnetLabels: List<String> by lazy {
        val model = listOf("src/main/assets/yamnet.tflite", "app/src/main/assets/yamnet.tflite").map(::File).first { it.exists() }
        ZipFile(model).use { zip ->
            zip.getInputStream(zip.getEntry("yamnet_label_list.txt")).bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    @Test
    fun everyMappedLabelExistsInYamnet() {
        assertEquals(521, yamnetLabels.size)
        val unknown = SoundTypes.mappedLabels - yamnetLabels.toSet()
        assertTrue("Labels YAMNet does not have: $unknown", unknown.isEmpty())
    }

    @Test
    fun everyTypeHasAtLeastOneLabelAndMostLabelsAreCovered() {
        val byType = yamnetLabels.mapNotNull(SoundTypes::of).toSet()
        assertEquals(SoundType.entries.toSet(), byType)
        val covered = yamnetLabels.count { SoundTypes.of(it) != null }
        assertTrue("Only $covered of 521 labels have a type", covered >= 480)
    }

    @Test
    fun commonNightSounds() {
        assertEquals(SoundType.DOG, SoundTypes.of("Bark"))
        assertEquals(SoundType.CAT, SoundTypes.of("Meow"))
        assertEquals(SoundType.DOOR, SoundTypes.of("Knock"))
        assertEquals(SoundType.FOOTSTEPS, SoundTypes.of("Walk, footsteps"))
        assertEquals(SoundType.RAIN, SoundTypes.of("Thunder"))
        assertEquals(SoundType.FIREWORKS, SoundTypes.of("Firecracker"))
        assertEquals(SoundType.MUSIC_TV, SoundTypes.of("Salsa music"))
        assertEquals(SoundType.MUSIC_TV, SoundTypes.of("Acoustic guitar"))
        assertEquals(SoundType.MUSIC_TV, SoundTypes.of("Television"))
        assertEquals(SoundType.FAN, SoundTypes.of("Mechanical fan"))
        assertEquals(SoundType.ROOM, SoundTypes.of("Inside, small room"))
        assertEquals(SoundType.MOVEMENT, SoundTypes.of("Rustle"))
        assertEquals(SoundType.ALARM, SoundTypes.of("Alarm clock"))
        // "Wind" is not a wind instrument.
        assertEquals(SoundType.WIND, SoundTypes.of("Wind"))
        assertNull(SoundTypes.of("Silence"))
        assertNull(SoundTypes.of(null))
        assertNull(SoundTypes.of(LabelMapper.UNKNOWN_LABEL))
    }

    @Test
    fun eventTypeFallsBackToTopLabelsSkippingRoomNoise() {
        assertEquals(SoundType.DOG, SoundTypes.ofEvent("Bark", listOf("Speech")))
        assertEquals(SoundType.DOOR, SoundTypes.ofEvent(LabelMapper.UNKNOWN_LABEL, listOf("Inside, small room", "Silence", "Door")))
        assertEquals(SoundType.DOOR, SoundTypes.ofEvent(LabelMapper.UNCLASSIFIED_LABEL, listOf("Slam")))
        // If there is only room noise, that is the type.
        assertEquals(SoundType.ROOM, SoundTypes.ofEvent("Inside, small room", listOf("Inside, small room", "Silence")))
        assertNull(SoundTypes.ofEvent(null, emptyList()))
    }

    @Test
    fun countsAreSortedByFrequencyThenEnumOrder() {
        val counts = SoundTypes.count(listOf(SoundType.DOOR, SoundType.DOG, null, SoundType.DOOR, SoundType.CAT, SoundType.DOG))
        assertEquals(listOf(SoundType.DOG to 2, SoundType.DOOR to 2, SoundType.CAT to 1), counts)
    }

    @Test
    fun soundTypesDoNotChangeCategoryDecisions() {
        // Presentation only: LabelMapper is unchanged (e.g. laughter counts as SPEECH, a sneeze as COUGH).
        assertEquals(LabelKind.SPEECH, LabelMapper.kind("Laughter"))
        assertEquals(LabelKind.COUGH, LabelMapper.kind("Sneeze"))
        assertEquals(LabelKind.OTHER, LabelMapper.kind("Bark"))
    }
}
