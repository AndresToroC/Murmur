package com.nochesonora.core.detection

import kotlin.math.roundToInt

/** Nearest-rank percentile over the first [count] values of [values]. Uses [scratch] to avoid allocating. */
internal fun percentile(values: FloatArray, count: Int, p: Double, scratch: FloatArray = FloatArray(count)): Float {
    require(count > 0) { "percentile of an empty list" }
    System.arraycopy(values, 0, scratch, 0, count)
    scratch.sort(0, count)
    val idx = ((count - 1) * p).roundToInt().coerceIn(0, count - 1)
    return scratch[idx]
}
