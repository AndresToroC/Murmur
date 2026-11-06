package com.nochesonora.core.classification

import com.nochesonora.core.audio.Pcm

/**
 * Ventanas de YAMNet de 0,975 s (15.600 muestras) con salto del 50 % (7.800), hechas a mano
 * porque `AUDIO_CLIPS` de MediaPipe no solapa (A2). La última ventana se rellena con ceros.
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

    /** Llena [out] (15.600) con la ventana [index] normalizada a float. */
    fun fillWindow(pcm: ShortArray, index: Int, out: FloatArray) {
        require(out.size == WINDOW_SAMPLES)
        val start = windowStart(index)
        val available = (pcm.size - start).coerceAtLeast(0)
        Pcm.toFloat(pcm, start, minOf(available, WINDOW_SAMPLES), out)
    }
}
