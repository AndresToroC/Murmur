package com.nochesonora.core.audio

import com.nochesonora.core.audio.RecordingOffset.Pause
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingOffsetTest {
    @Test
    fun withoutPausesIsElapsedTime() {
        assertEquals(60_000L, RecordingOffset.offsetMs(1_000, 61_000, emptyList()))
    }

    @Test
    fun subtractsPreviousPausesOnly() {
        val pauses = listOf(Pause(10_000, 20_000), Pause(100_000, 130_000))
        assertEquals(50_000L - 10_000, RecordingOffset.offsetMs(0, 50_000, pauses))
        assertEquals(200_000L - 10_000 - 30_000, RecordingOffset.offsetMs(0, 200_000, pauses))
    }

    @Test
    fun openPauseCountsUntilTheInstant() {
        assertEquals(10_000L, RecordingOffset.offsetMs(0, 30_000, listOf(Pause(10_000, null))))
    }
}
