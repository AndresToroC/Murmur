package com.nochesonora.transcription

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Prepares a speech clip for the phone's recognizer (pure logic, tested on the JVM).
 *
 * Sleep talk is usually quiet and broken up, and the clip starts with seconds of silence
 * (pre-roll). As is, the recognizer discards it ("not understood") or stops at the first pause
 * and returns only part of it. So:
 *  1. High-pass filter (~80 Hz): removes hum and DC offset.
 *  2. Detects the voiced stretches by energy, relative to the clip's own noise.
 *  3. Joins stretches separated by short pauses into phrases and returns them separately, so
 *     each one is recognized on its own (the recognizer no longer cuts off at the first pause).
 *  4. Raises each phrase's volume to a conversational level, with a soft limiter.
 *  5. Adds a little silence before and after, so the recognizer detects the start and end of
 *     the phrase.
 */
object SpeechPrep {
    const val SAMPLE_RATE = 16_000
    /** 30 ms per frame. */
    const val FRAME = 480

    private const val MERGE_GAP_MS = 500
    private const val PAD_BEFORE_MS = 250
    private const val PAD_AFTER_MS = 400
    /** A stretch with less voice than this (a knock, a sigh) is not recognized on its own. */
    private const val MIN_VOICED_MS = 150
    /** Longer phrases are split at their quietest point. */
    private const val MAX_PHRASE_MS = 20_000
    /** Cap on recognitions per clip; beyond it, the closest phrases are merged. */
    const val MAX_PHRASES = 12

    private const val LEAD_SILENCE_MS = 300
    private const val TAIL_SILENCE_MS = 1_200

    /** Target RMS level of the voice: -20 dBFS. */
    private const val TARGET_RMS = 3_277.0
    /** Maximum gain: +30 dB. */
    private const val MAX_GAIN = 31.6
    /** Below this (-66 dBFS) nothing counts as voice, even if the room is very quiet. */
    private const val ABS_MIN_RMS = 16.0
    /** Voice must be at least 6 dB above the clip's noise. */
    private const val NOISE_RATIO = 2.0

    /** Phrases ready for the recognizer (PCM16 mono 16 kHz), in order. Empty if there is no audio. */
    fun phrases(samples: ShortArray): List<ShortArray> {
        if (samples.isEmpty()) return emptyList()
        val x = highPass(samples)
        val frameRms = frameRms(x)
        val ranges = voicedRanges(frameRms).ifEmpty {
            // No clear contrast between voice and noise (all silence, or all voice): the whole clip.
            splitLong(0 until frameRms.size, frameRms, MAX_PHRASE_MS * SAMPLE_RATE / 1000 / FRAME)
        }
        return ranges.map { r ->
            val start = r.first * FRAME
            val end = minOf(x.size, (r.last + 1) * FRAME)
            val gain = gainFor(x, frameRms, r)
            withSilence(x, start, end, gain)
        }
    }

    /** One-pole high-pass filter at ~80 Hz; also removes DC. */
    internal fun highPass(samples: ShortArray): DoubleArray {
        val a = 0.9695
        val out = DoubleArray(samples.size)
        var prevX = samples[0].toDouble()
        var prevY = 0.0
        for (i in samples.indices) {
            val xi = samples[i].toDouble()
            prevY = a * (prevY + xi - prevX)
            prevX = xi
            out[i] = prevY
        }
        return out
    }

    internal fun frameRms(x: DoubleArray): DoubleArray {
        val n = (x.size + FRAME - 1) / FRAME
        return DoubleArray(n) { f ->
            val from = f * FRAME
            val to = minOf(x.size, from + FRAME)
            var sum = 0.0
            for (i in from until to) sum += x[i] * x[i]
            sqrt(sum / (to - from))
        }
    }

    /** Frame ranges (with padding) that make up each phrase. */
    internal fun voicedRanges(frameRms: DoubleArray): List<IntRange> {
        if (frameRms.isEmpty()) return emptyList()
        val noise = percentile(frameRms, 0.2)
        val threshold = maxOf(noise * NOISE_RATIO, ABS_MIN_RMS)
        val voiced = BooleanArray(frameRms.size) { frameRms[it] >= threshold }
        val frameMs = FRAME * 1000 / SAMPLE_RATE

        // Continuous voiced stretches.
        val runs = mutableListOf<IntRange>()
        var i = 0
        while (i < voiced.size) {
            if (!voiced[i]) { i++; continue }
            val s = i
            while (i < voiced.size && voiced[i]) i++
            runs += s until i
        }
        if (runs.isEmpty()) return emptyList()

        var mergeGap = MERGE_GAP_MS / frameMs
        var phrases = merge(runs, mergeGap)
        // Isolated stretches that are too short are dropped, unless nothing would be left.
        val minVoiced = MIN_VOICED_MS / frameMs
        phrases = phrases.filter { p -> p.voicedFrames(voiced) >= minVoiced }.ifEmpty { phrases }
        while (phrases.size > MAX_PHRASES) {
            mergeGap *= 2
            phrases = merge(phrases, mergeGap)
        }

        val padBefore = PAD_BEFORE_MS / frameMs
        val padAfter = PAD_AFTER_MS / frameMs
        val padded = phrases.map { maxOf(0, it.first - padBefore)..minOf(frameRms.size - 1, it.last + padAfter) }
        // Padding can make two phrases touch: audio is not repeated between them.
        val disjoint = padded.mapIndexed { k, r ->
            val prevEnd = if (k == 0) -1 else padded[k - 1].last
            maxOf(r.first, prevEnd + 1)..r.last
        }.filter { !it.isEmpty() }
        return disjoint.flatMap { splitLong(it, frameRms, MAX_PHRASE_MS / frameMs) }
    }

    private fun IntRange.voicedFrames(voiced: BooleanArray) = count { voiced[it] }

    private fun merge(ranges: List<IntRange>, gapFrames: Int): List<IntRange> {
        val out = mutableListOf<IntRange>()
        for (r in ranges) {
            val last = out.lastOrNull()
            if (last != null && r.first - last.last - 1 <= gapFrames) out[out.size - 1] = last.first..r.last else out += r
        }
        return out
    }

    /** Splits a long range at its quietest frame (within the middle half), recursively. */
    private fun splitLong(r: IntRange, frameRms: DoubleArray, maxFrames: Int): List<IntRange> {
        val len = r.last - r.first + 1
        if (len <= maxFrames) return listOf(r)
        val from = r.first + len / 4
        val to = r.last - len / 4
        val cut = (from..to).minBy { frameRms[it] }
        return splitLong(r.first until cut, frameRms, maxFrames) + splitLong(cut..r.last, frameRms, maxFrames)
    }

    /** Gain that brings the phrase's voice to [TARGET_RMS]; never lowers the volume. */
    private fun gainFor(x: DoubleArray, frameRms: DoubleArray, r: IntRange): Double {
        // Voice level: root mean square of the loudest half of the phrase's frames.
        val loud = r.map { frameRms[it] }.sortedDescending().let { it.take(maxOf(1, it.size / 2)) }
        val rms = sqrt(loud.sumOf { it * it } / loud.size)
        if (rms <= 0.0) return 1.0
        return (TARGET_RMS / rms).coerceIn(1.0, MAX_GAIN)
    }

    /** The phrase with gain and limiter, between silences (with minimal noise, not exact zeros). */
    private fun withSilence(x: DoubleArray, start: Int, end: Int, gain: Double): ShortArray {
        val lead = LEAD_SILENCE_MS * SAMPLE_RATE / 1000
        val tail = TAIL_SILENCE_MS * SAMPLE_RATE / 1000
        val out = ShortArray(lead + (end - start) + tail)
        var seed = 0x2545F491
        for (i in out.indices) {
            // ±2 LSB noise: some recognizers treat exact zeros as "no audio".
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
            out[i] = ((seed and 3) - 2).toShort()
        }
        for (i in start until end) out[lead + i - start] = limit(x[i] * gain)
        return out
    }

    /** Soft limiter: linear up to 70 % of full scale, then curves without clipping. */
    internal fun limit(v: Double): Short {
        val full = 32_767.0
        val knee = 0.7 * full
        val a = abs(v)
        val y = if (a <= knee) a else knee + (full - knee) * tanh((a - knee) / (full - knee))
        return (if (v < 0) -y else y).toInt().toShort()
    }

    private fun percentile(values: DoubleArray, p: Double): Double {
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).toInt()]
    }

    /** PCM16 little-endian ↔ samples. */
    fun toSamples(pcm: ByteArray): ShortArray =
        ShortArray(pcm.size / 2) { ((pcm[2 * it].toInt() and 0xFF) or (pcm[2 * it + 1].toInt() shl 8)).toShort() }

    fun toBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            out[2 * i] = samples[i].toInt().toByte()
            out[2 * i + 1] = (samples[i].toInt() shr 8).toByte()
        }
        return out
    }
}
