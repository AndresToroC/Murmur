package com.nochesonora.core.detection

/**
 * Capa 1: línea base adaptativa del ruido de fondo.
 *
 * Calibra con el p20 de los primeros 60 s y después sigue al p20 de una ventana
 * deslizante de 30 s con una EMA lenta (τ = 120 s). El llamador decide qué bloques
 * alimentan la base (los de un evento no lo hacen, tampoco durante la calibración).
 *
 * Desde los 5 s de calentamiento hay una base provisional (p20 de lo calibrado hasta ese
 * momento), para que el primer minuto de la noche no quede sin detección.
 */
class BaselineTracker(private val config: DetectorConfig) {
    private val calibration = FloatArray(config.calibrationBlocks)
    private var calibrationCount = 0
    /** Bloques transcurridos de calibración, incluidos los de eventos (no alimentan la base). */
    private var calibrationElapsed = 0

    private val window = FloatArray(config.percentileWindowBlocks)
    private var windowPos = 0
    private var windowCount = 0
    private val scratch = FloatArray(maxOf(config.calibrationBlocks, config.percentileWindowBlocks))

    val isCalibrated: Boolean get() = calibrationElapsed >= calibration.size

    val calibrationProgress: Float
        get() = if (calibration.isEmpty()) 1f else minOf(1f, calibrationElapsed.toFloat() / calibration.size)

    /** null durante el calentamiento; provisional hasta terminar la calibración. */
    var baselineDb: Float? = null
        private set

    /** p80 − p20 de la calibración (dato de diagnóstico). */
    var variabilityDb: Float? = null
        private set

    val thresholdDb: Float? get() = baselineDb?.plus(config.sensitivityDb)

    /**
     * Procesa un bloque. Solo los que llegan con [track] = true alimentan la base; durante
     * el calentamiento se usan todos, porque todavía no puede haber eventos.
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
        if (n == 0) return // imposible en la práctica: el calentamiento siempre aporta bloques
        val p20 = percentile(calibration, n, config.percentile, scratch)
        val p80 = percentile(calibration, n, 1.0 - config.percentile, scratch)
        baselineDb = p20
        variabilityDb = p80 - p20
        // La ventana arranca con los últimos 30 s de la calibración.
        val start = maxOf(0, n - window.size)
        for (i in start until n) push(calibration[i])
    }

    private fun push(levelDb: Float) {
        window[windowPos] = levelDb
        windowPos = (windowPos + 1) % window.size
        if (windowCount < window.size) windowCount++
    }
}
