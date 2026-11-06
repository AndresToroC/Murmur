package com.nochesonora.core.episodes

import com.nochesonora.core.classification.Category

data class EventLite(
    val id: Long,
    val category: Category,
    val userCategory: Category?,
    val startedAt: Long,
    val endedAt: Long,
) {
    val effectiveCategory: Category get() = userCategory ?: category
}

data class EpisodeLite(
    val category: Category,
    val startedAt: Long,
    val endedAt: Long,
    val eventIds: List<Long>,
)

/** Une eventos consecutivos de la misma categoría efectiva separados por menos de 30 s (A8). */
object EpisodeGrouper {
    const val MAX_GAP_MS = 30_000L

    fun group(events: List<EventLite>): List<EpisodeLite> {
        val result = ArrayList<EpisodeLite>()
        var category: Category? = null
        var start = 0L
        var end = 0L
        val ids = ArrayList<Long>()

        fun flush() {
            val c = category ?: return
            result += EpisodeLite(c, start, end, ids.toList())
            ids.clear()
        }

        for (e in events.sortedWith(compareBy({ it.startedAt }, { it.id }))) {
            val c = e.effectiveCategory
            if (category == c && e.startedAt - end < MAX_GAP_MS) {
                end = maxOf(end, e.endedAt)
            } else {
                flush()
                category = c
                start = e.startedAt
                end = e.endedAt
            }
            ids += e.id
        }
        flush()
        return result
    }
}
