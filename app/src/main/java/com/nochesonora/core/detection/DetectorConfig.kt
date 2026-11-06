package com.nochesonora.core.detection

import com.nochesonora.core.audio.Pcm

/** Margin above the baseline that opens an event. */
enum class Sensitivity(val marginDb: Int) {
    BAJA(14),
    MEDIA(10),
    ALTA(7),
}

/** All numeric parameters of layers 1 and 2 (plan §4.3 and §4.4). */
data class DetectorConfig(
    val sampleRate: Int = Pcm.SAMPLE_RATE,
    val blockMs: Long = 100,
    // Layer 1: baseline
    val calibrationMs: Long = 60_000,
    /** After this time there is a provisional baseline and events are detected even while calibration continues. */
    val warmupMs: Long = 5_000,
    val percentileWindowMs: Long = 30_000,
    val percentile: Double = 0.20,
    val emaTauMs: Long = 120_000,
    val sensitivityDb: Float = Sensitivity.MEDIA.marginDb.toFloat(),
    // Layer 2: events
    val onsetMs: Long = 300,
    val hangoverMs: Long = 2_500,
    val maxEventMs: Long = 60_000,
    val preRollMs: Long = 3_000,
    val postRollMs: Long = 2_000,
    val ringBufferMs: Long = 5_000,
) {
    val blockSamples: Int get() = (sampleRate * blockMs / 1000).toInt()
    val calibrationBlocks: Int get() = (calibrationMs / blockMs).toInt()
    val warmupBlocks: Int get() = (warmupMs / blockMs).toInt()
    val percentileWindowBlocks: Int get() = (percentileWindowMs / blockMs).toInt()
    val onsetBlocks: Int get() = (onsetMs / blockMs).toInt()
    val hangoverBlocks: Int get() = (hangoverMs / blockMs).toInt()
    val emaAlpha: Float get() = blockMs.toFloat() / emaTauMs
    fun samples(ms: Long): Int = (ms * sampleRate / 1000).toInt()
}
