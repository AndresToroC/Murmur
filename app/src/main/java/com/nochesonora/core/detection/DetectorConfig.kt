package com.nochesonora.core.detection

import com.nochesonora.core.audio.Pcm

/** Margen sobre la línea base que abre un evento. */
enum class Sensitivity(val marginDb: Int) {
    BAJA(14),
    MEDIA(10),
    ALTA(7),
}

/** Todos los parámetros numéricos de las capas 1 y 2 (plan §4.3 y §4.4). */
data class DetectorConfig(
    val sampleRate: Int = Pcm.SAMPLE_RATE,
    val blockMs: Long = 100,
    // Capa 1: línea base
    val calibrationMs: Long = 60_000,
    /** Tras este tiempo ya hay base provisional y se detectan eventos aunque siga calibrando. */
    val warmupMs: Long = 5_000,
    val percentileWindowMs: Long = 30_000,
    val percentile: Double = 0.20,
    val emaTauMs: Long = 120_000,
    val sensitivityDb: Float = Sensitivity.MEDIA.marginDb.toFloat(),
    // Capa 2: eventos
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
