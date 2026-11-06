package com.nochesonora.core.detection

import kotlin.math.roundToInt

/** Percentil por rango más cercano sobre los primeros [count] valores de [values]. Usa [scratch] para no reservar memoria. */
internal fun percentile(values: FloatArray, count: Int, p: Double, scratch: FloatArray = FloatArray(count)): Float {
    require(count > 0) { "percentile de una lista vacía" }
    System.arraycopy(values, 0, scratch, 0, count)
    scratch.sort(0, count)
    val idx = ((count - 1) * p).roundToInt().coerceIn(0, count - 1)
    return scratch[idx]
}
