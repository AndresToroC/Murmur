package com.nochesonora.data

import java.util.concurrent.TimeUnit

enum class Retention(val days: Int?) {
    FOREVER(null),
    DAYS_7(7),
    DAYS_30(30),
}

data class RetentionCandidate(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val isFavorite: Boolean,
    val inProgress: Boolean,
)

/** Pure logic for which sessions to delete. Never deletes favorites or the session in progress. */
object RetentionPolicy {
    fun idsToDelete(now: Long, retention: Retention, sessions: List<RetentionCandidate>): List<Long> {
        val days = retention.days ?: return emptyList()
        val limit = now - TimeUnit.DAYS.toMillis(days.toLong())
        return sessions
            .filter { !it.isFavorite && !it.inProgress && (it.endedAt ?: it.startedAt) < limit }
            .map { it.id }
    }
}
