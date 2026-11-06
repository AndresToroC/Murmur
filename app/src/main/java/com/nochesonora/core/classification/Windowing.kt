package com.nochesonora.core.classification

import com.nochesonora.core.audio.Pcm

/**
 * YAMNet windows of 0.975 s (15,600 samples) with a 50 % hop (7,800), built by hand
 * because MediaPipe's `AUDIO_CLIPS` does not overlap (A2). The last window is zero-padded.
 */
object Windowing {
    const val WINDOW_SAMPLES = 15_600
    const val HOP_SAMPLES = 7_800

    fun windowCount(totalSamples: Int): Int {
        if (totalSamples <= WINDOW_SAMPLES) return 1
        val extra = totalSamples - WINDOW_SAMPLES
        return 1 + (extra + HOP_SAMPLES - 1) / HOP_SAMPLES
    }

    fun windowStart(index: Int): Int = index * HOP_SAMPLES

    /** Fills [out] (15,600) with window [index] normalized to float. */
    fun fillWindow(pcm: ShortArray, index: Int, out: FloatArray) {
        require(out.size == WINDOW_SAMPLES)
        val start = windowStart(index)
        val available = (pcm.size - start).coerceAtLeast(0)
        Pcm.toFloat(pcm, start, minOf(available, WINDOW_SAMPLES), out)
    }
}
