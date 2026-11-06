package com.nochesonora.core.audio

/**
 * Posición de un instante dentro de `full.aac` (§4.7). Durante las pausas no se graba, así que
 * offset = hora − inicio de la sesión − Σ duración de las pausas ya terminadas antes de esa hora.
 */
object RecordingOffset {
    data class Pause(val startedAt: Long, val endedAt: Long?)

    fun offsetMs(sessionStartedAt: Long, atMs: Long, pauses: List<Pause>): Long {
        var paused = 0L
        for (p in pauses) {
            if (p.startedAt >= atMs) continue
            val end = minOf(p.endedAt ?: atMs, atMs)
            paused += (end - p.startedAt).coerceAtLeast(0)
        }
        return (atMs - sessionStartedAt - paused).coerceAtLeast(0)
    }
}
