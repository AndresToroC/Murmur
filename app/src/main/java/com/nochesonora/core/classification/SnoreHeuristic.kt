package com.nochesonora.core.classification

import com.nochesonora.core.audio.Pcm
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class SnoreResult(
    val isPeriodic: Boolean,
    val isLowFrequency: Boolean,
    val lowFrequencyRatio: Float,
    val peakCount: Int,
) {
    val isSnoreLike: Boolean get() = isPeriodic && isLowFrequency
}

/**
 * Heurística de ronquido (plan §4.5): golpes de energía periódicos cada 2–6 s y energía
 * concentrada por debajo de 500 Hz.
 *
 * Los picos se toman de a uno por ráfaga (tramo continuo sobre mediana + 6 dB) y las ráfagas
 * a menos de 1,5 s de la anterior se funden con ella. Así un pulso cada 1 s queda como una
 * única ráfaga larga y no parece un ronquido cada 2 s.
 */
object SnoreHeuristic {
    private const val BLOCK_MS = 100
    private const val PEAK_MARGIN_DB = 6f
    private const val MIN_SEPARATION_MS = 1_500
    private const val MIN_INTERVAL_MS = 2_000
    private const val MAX_INTERVAL_MS = 6_000
    private const val MIN_INTERVALS = 3
    private const val MAX_CV = 0.35
    private const val LOW_FREQ_HZ = 500.0
    private const val MIN_LOW_FREQ_RATIO = 0.6f

    fun analyze(pcm: ShortArray, sampleRate: Int = Pcm.SAMPLE_RATE): SnoreResult {
        val ratio = lowFrequencyRatio(pcm, sampleRate)
        val peaks = peaks(envelope(pcm, sampleRate))
        return SnoreResult(
            isPeriodic = isPeriodic(peaks),
            isLowFrequency = ratio >= MIN_LOW_FREQ_RATIO,
            lowFrequencyRatio = ratio,
            peakCount = peaks.size,
        )
    }

    /** Nivel en dB por bloque de 100 ms, suavizado con una media de 3 bloques. */
    internal fun envelope(pcm: ShortArray, sampleRate: Int): FloatArray {
        val block = sampleRate * BLOCK_MS / 1000
        val n = pcm.size / block
        val raw = FloatArray(n) { Pcm.rmsDb(pcm, it * block, block) }
        return FloatArray(n) { i ->
            val from = maxOf(0, i - 1)
            val to = minOf(n - 1, i + 1)
            var sum = 0f
            for (k in from..to) sum += raw[k]
            sum / (to - from + 1)
        }
    }

    /** Índices de bloque de los picos (uno por ráfaga, ráfagas cercanas fundidas). */
    internal fun peaks(env: FloatArray): List<Int> {
        if (env.size < 3) return emptyList()
        val median = env.sortedArray()[env.size / 2]
        val threshold = median + PEAK_MARGIN_DB

        val burstPeaks = ArrayList<Int>()
        var inBurst = false
        var best = -1
        for (i in env.indices) {
            if (env[i] >= threshold) {
                if (!inBurst || env[i] > env[best]) best = i
                inBurst = true
            } else if (inBurst) {
                burstPeaks += best
                inBurst = false
            }
        }
        if (inBurst) burstPeaks += best

        val minSepBlocks = MIN_SEPARATION_MS / BLOCK_MS
        val merged = ArrayList<Int>()
        var previous = Int.MIN_VALUE / 2
        for (p in burstPeaks) {
            if (merged.isNotEmpty() && p - previous < minSepBlocks) {
                if (env[p] > env[merged.last()]) merged[merged.size - 1] = p
            } else {
                merged += p
            }
            previous = p
        }
        return merged
    }

    internal fun isPeriodic(peaks: List<Int>): Boolean {
        if (peaks.size < MIN_INTERVALS + 1) return false
        val intervals = peaks.zipWithNext { a, b -> ((b - a) * BLOCK_MS).toDouble() }
        if (intervals.any { it < MIN_INTERVAL_MS || it > MAX_INTERVAL_MS }) return false
        val mean = intervals.average()
        val variance = intervals.sumOf { (it - mean) * (it - mean) } / intervals.size
        return sqrt(variance) / mean < MAX_CV
    }

    /** Energía tras un paso bajo biquad de 2º orden (fc = 500 Hz, Q = 0,707) / energía total. */
    internal fun lowFrequencyRatio(pcm: ShortArray, sampleRate: Int): Float {
        val w0 = 2.0 * PI * LOW_FREQ_HZ / sampleRate
        val alpha = sin(w0) / (2.0 * 0.7071)
        val cosW0 = cos(w0)
        val a0 = 1.0 + alpha
        val b0 = (1.0 - cosW0) / 2.0 / a0
        val b1 = (1.0 - cosW0) / a0
        val b2 = b0
        val a1 = -2.0 * cosW0 / a0
        val a2 = (1.0 - alpha) / a0

        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        var low = 0.0
        var total = 0.0
        for (s in pcm) {
            val x = s.toDouble()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            low += y * y
            total += x * x
        }
        return if (total <= 0.0) 0f else (low / total).coerceIn(0.0, 1.0).toFloat()
    }
}
