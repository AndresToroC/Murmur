package com.nochesonora.core.episodes

import com.nochesonora.core.classification.Category
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeGrouperTest {
    private fun ev(id: Long, cat: Category, startS: Int, durS: Int = 5, user: Category? = null) =
        EventLite(id, cat, user, startS * 1000L, (startS + durS) * 1000L)

    @Test
    fun gapOf29sJoins() {
        val eps = EpisodeGrouper.group(listOf(ev(1, Category.SNORING, 0), ev(2, Category.SNORING, 34)))
        assertEquals(1, eps.size)
        assertEquals(listOf(1L, 2L), eps[0].eventIds)
        assertEquals(0L, eps[0].startedAt)
        assertEquals(39_000L, eps[0].endedAt)
    }

    @Test
    fun gapOf31sSplits() {
        val eps = EpisodeGrouper.group(listOf(ev(1, Category.SNORING, 0), ev(2, Category.SNORING, 36)))
        assertEquals(2, eps.size)
    }

    @Test
    fun interleavedCategoryBreaks() {
        val eps = EpisodeGrouper.group(
            listOf(ev(1, Category.SNORING, 0), ev(2, Category.COUGH, 8), ev(3, Category.SNORING, 16)),
        )
        assertEquals(listOf(Category.SNORING, Category.COUGH, Category.SNORING), eps.map { it.category })
    }

    @Test
    fun userCategoryWins() {
        val eps = EpisodeGrouper.group(
            listOf(ev(1, Category.SNORING, 0), ev(2, Category.OTHER, 8, user = Category.SNORING), ev(3, Category.SNORING, 16)),
        )
        assertEquals(1, eps.size)
        assertEquals(3, eps[0].eventIds.size)
    }

    @Test
    fun unsortedInputIsSorted() {
        val eps = EpisodeGrouper.group(listOf(ev(2, Category.SPEECH, 20), ev(1, Category.SPEECH, 0)))
        assertEquals(listOf(1L, 2L), eps.single().eventIds)
    }

    @Test
    fun emptyGivesNothing() {
        assertEquals(emptyList<EpisodeLite>(), EpisodeGrouper.group(emptyList()))
    }
}
