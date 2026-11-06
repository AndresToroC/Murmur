package com.nochesonora.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionPolicyTest {
    private val day = 24 * 3600 * 1000L
    private val now = 100 * day

    private fun s(id: Long, endedDaysAgo: Int?, fav: Boolean = false, inProgress: Boolean = false, startedDaysAgo: Int = 0) =
        RetentionCandidate(id, now - startedDaysAgo * day, endedDaysAgo?.let { now - it * day }, fav, inProgress)

    private val sessions = listOf(
        s(1, 3),
        s(2, 8),
        s(3, 31),
        s(4, 40, fav = true),
        s(5, null, inProgress = true, startedDaysAgo = 50),
        s(6, null, startedDaysAgo = 45), // no recorded end: the start counts
    )

    @Test
    fun keepForever() {
        assertEquals(emptyList<Long>(), RetentionPolicy.idsToDelete(now, Retention.SIEMPRE, sessions))
    }

    @Test
    fun sevenDays() {
        assertEquals(listOf(2L, 3L, 6L), RetentionPolicy.idsToDelete(now, Retention.DIAS_7, sessions))
    }

    @Test
    fun thirtyDays() {
        assertEquals(listOf(3L, 6L), RetentionPolicy.idsToDelete(now, Retention.DIAS_30, sessions))
    }
}
