package com.nochesonora.core

import com.nochesonora.core.audio.Pcm
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Señales sintéticas para los tests (16 kHz, PCM16). */
object SignalGen {
    const val SR = Pcm.SAMPLE_RATE

    fun samples(seconds: Double): Int = (seconds * SR).roundToInt()

    fun silence(seconds: Double) = ShortArray(samples(seconds))

    fun sine(freqHz: Double, seconds: Double, amplitude: Double = 32767.0): ShortArray =
        ShortArray(samples(seconds)) { i -> (amplitude * sin(2 * PI * freqHz * i / SR)).roundToInt().toShort() }

    fun whiteNoise(seconds: Double, amplitude: Double, seed: Int = 1): ShortArray {
        val rnd = Random(seed)
        return ShortArray(samples(seconds)) { ((rnd.nextDouble() * 2 - 1) * amplitude).roundToInt().toShort() }
    }

    /** Suma [src] en [dst] a partir de [atSecond], con saturación. */
    fun mixInto(dst: ShortArray, src: ShortArray, atSecond: Double) {
        val start = samples(atSecond)
        for (i in src.indices) {
            val j = start + i
            if (j !in dst.indices) break
            dst[j] = (dst[j] + src[i]).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /** Ráfaga de graves (tonos de 120/150/180 Hz) con envolvente de Hann: imita un ronquido. */
    fun lowBurst(seconds: Double, amplitude: Double): ShortArray {
        val n = samples(seconds)
        return ShortArray(n) { i ->
            val env = 0.5 - 0.5 * kotlin.math.cos(2 * PI * i / n)
            val t = i.toDouble() / SR
            val v = sin(2 * PI * 120 * t) + sin(2 * PI * 150 * t + 1.0) + sin(2 * PI * 180 * t + 2.0)
            (amplitude * env * v / 3).roundToInt().toShort()
        }
    }

    /** Ráfaga de "voz": tonos entre 400 y 2.500 Hz. */
    fun voiceBurst(seconds: Double, amplitude: Double): ShortArray {
        val n = samples(seconds)
        return ShortArray(n) { i ->
            val env = 0.5 - 0.5 * kotlin.math.cos(2 * PI * i / n)
            val t = i.toDouble() / SR
            val v = sin(2 * PI * 450 * t) + sin(2 * PI * 1200 * t + 0.5) + sin(2 * PI * 2400 * t + 1.5)
            (amplitude * env * v / 3).roundToInt().toShort()
        }
    }
}
