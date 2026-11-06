package com.nochesonora.core.detection

/**
 * Layer 1: adaptive baseline of the background noise.
 *
 * Calibrates with the p20 of the first 60 s, then follows the p20 of a 30 s sliding
 * window through a slow EMA (τ = 120 s). The caller decides which blocks feed the
 * baseline (event blocks do not, not even during calibration).
 *
 * From the 5 s warm-up onward there is a provisional baseline (p20 of what has been
 * calibrated so far), so the first minute of the night is not left without detection.
 */
class BaselineTracker(private val config: DetectorConfig) {
    private val calibration = FloatArray(config.calibrationBlocks)
    private var calibrationCount = 0
    /** Calibration blocks elapsed, including event blocks (which do not feed the baseline). */
    private var calibrationElapsed = 0

    private val window = FloatArray(config.percentileWindowBlocks)
    private var windowPos = 0
    private var windowCount = 0
    private val scratch = FloatArray(maxOf(config.calibrationBlocks, config.percentileWindowBlocks))

    val isCalibrated: Boolean get() = calibrationElapsed >= calibration.size

    val calibrationProgress: Float
        get() = if (calibration.isEmpty()) 1f else minOf(1f, calibrationElapsed.toFloat() / calibration.size)

    /** null during warm-up; provisional until calibration finishes. */
    var baselineDb: Float? = null
        private set

    /** p80 − p20 of the calibration (diagnostic value). */
    var variabilityDb: Float? = null
        private set

    val thresholdDb: Float? get() = baselineDb?.plus(config.sensitivityDb)

    /**
     * Processes one block. Only blocks with [track] = true feed the baseline; during
     * warm-up all of them are used, because there cannot be any events yet.
     */
    fun onBlock(levelDb: Float, track: Boolean) {
        if (!isCalibrated) {
            calibrationElapsed++
            if ((track || calibrationElapsed <= config.warmupBlocks) && calibrationCount < calibration.size) {
                calibration[calibrationCount++] = levelDb
            }
            if (isCalibrated) {
                finishCalibration()
            } else if (calibrationElapsed >= config.warmupBlocks && calibrationCount > 0) {
                baselineDb = percentile(calibration, calibrationCount, config.percentile, scratch)
            }
            return
        }
        if (!track) return
        push(levelDb)
        val target = percentile(window, windowCount, config.percentile, scratch)
        val current = baselineDb ?: target
        baselineDb = current + (target - current) * config.emaAlpha
    }

    private fun finishCalibration() {
        val n = calibrationCount
        if (n == 0) return // impossible in practice: warm-up always contributes blocks
        val p20 = percentile(calibration, n, config.percentile, scratch)
        val p80 = percentile(calibration, n, 1.0 - config.percentile, scratch)
        baselineDb = p20
        variabilityDb = p80 - p20
        // The window starts with the last 30 s of calibration.
        val start = maxOf(0, n - window.size)
        for (i in start until n) push(calibration[i])
    }

    private fun push(levelDb: Float) {
        window[windowPos] = levelDb
        windowPos = (windowPos + 1) % window.size
        if (windowCount < window.size) windowCount++
    }
}
