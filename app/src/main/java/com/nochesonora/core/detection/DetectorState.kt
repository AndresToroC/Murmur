package com.nochesonora.core.detection

enum class DetectorState { CALIBRATING, LISTENING, ONSET, EVENT }

/** Event closed by layer 2, ready to classify. */
class ClosedEvent(
    /** Actual start (first onset block), in wall-clock ms. */
    val startedAtMs: Long,
    /** Actual end (last block above the threshold), in wall-clock ms. */
    val endedAtMs: Long,
    /** Time of the first sample of [pcm] (start − pre-roll). */
    val clipStartedAtMs: Long,
    val peakDb: Float,
    val pcm: ShortArray,
    /** true if it was cut for reaching the maximum duration. */
    val wasCut: Boolean,
)

class DetectorOutput(
    val state: DetectorState,
    val levelDb: Float,
    val baselineDb: Float?,
    val thresholdDb: Float?,
    val calibrationProgress: Float,
    val closedEvent: ClosedEvent?,
)
