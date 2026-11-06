package com.nochesonora.core.audio

import kotlin.math.log10
import kotlin.math.sqrt

/** Utilidades de PCM16 mono a 16 kHz. */
object Pcm {
    const val SAMPLE_RATE = 16_000
    const val FLOOR_DB = -100f
    private const val FULL_SCALE = 32768.0

    fun msToSamples(ms: Long): Int = (ms * SAMPLE_RATE / 1000).toInt()

    fun samplesToMs(samples: Long): Long = samples * 1000 / SAMPLE_RATE

    /** Nivel RMS en dBFS, con piso de [FLOOR_DB] para bloques de ceros. */
    fun rmsDb(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset): Float {
        if (length <= 0) return FLOOR_DB
        var sum = 0.0
        for (i in offset until offset + length) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / length)
        if (rms <= 0.0) return FLOOR_DB
        val db = 20.0 * log10(rms / FULL_SCALE)
        return if (db < FLOOR_DB) FLOOR_DB else db.toFloat()
    }

    /** Copia PCM16 a float en [-1, 1). Las posiciones de [out] más allá de [length] se rellenan con ceros. */
    fun toFloat(samples: ShortArray, offset: Int, length: Int, out: FloatArray) {
        val n = minOf(length, out.size)
        for (i in 0 until n) out[i] = samples[offset + i] / FULL_SCALE.toFloat()
        for (i in n until out.size) out[i] = 0f
    }
}
