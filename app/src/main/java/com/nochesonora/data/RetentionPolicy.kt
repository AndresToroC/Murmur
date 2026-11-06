package com.nochesonora.data

import java.util.concurrent.TimeUnit

enum class Retention(val days: Int?) {
    SIEMPRE(null),
    DIAS_7(7),
    DIAS_30(30),
}

data class RetentionCandidate(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val isFavorite: Boolean,
    val inProgress: Boolean,
)

/** Lógica pura de qué sesiones borrar. Nunca borra favoritas ni la sesión en curso. */
object RetentionPolicy {
    fun idsToDelete(now: Long, retention: Retention, sessions: List<RetentionCandidate>): List<Long> {
        val days = retention.days ?: return emptyList()
        val limit = now - TimeUnit.DAYS.toMillis(days.toLong())
        return sessions
            .filter { !it.isFavorite && !it.inProgress && (it.endedAt ?: it.startedAt) < limit }
            .map { it.id }
    }
}
