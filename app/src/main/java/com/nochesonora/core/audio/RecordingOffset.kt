package com.nochesonora.core.audio

/**
 * Position of an instant inside `full.aac` (§4.7). Nothing is recorded during pauses, so
 * offset = time − session start − Σ duration of pauses that ended before that time.
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
