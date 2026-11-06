package com.nochesonora.ui.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** What trends need from a night (independent of Room and Android). */
data class NightStats(
    val sessionId: Long,
    val startedAtMs: Long,
    val snoreMs: Long,
    val snoreEvents: Int,
    val speech: Int,
    val cough: Int,
    val other: Int,
) {
    val totalEvents: Int get() = snoreEvents + speech + cough + other
}

/** One day of the range: the nights that started that day, summed. */
data class DayPoint(
    val date: LocalDate,
    val nights: Int,
    val snoreMs: Long,
    val speech: Int,
    val cough: Int,
    val totalEvents: Int,
    /** The night that started latest that day (the one opened when the bar is tapped). */
    val latestSessionId: Long?,
) {
    val hasNights: Boolean get() = nights > 0
}

/** Totals and per-night averages for a period. */
data class PeriodStats(
    val nights: Int,
    val totalSnoreMs: Long,
    val totalSpeech: Int,
    val totalCough: Int,
) {
    val avgSnoreMs: Long get() = if (nights == 0) 0 else totalSnoreMs / nights
    val avgSpeech: Double get() = if (nights == 0) 0.0 else totalSpeech.toDouble() / nights
    val avgCough: Double get() = if (nights == 0) 0.0 else totalCough.toDouble() / nights
}

enum class Direction { UP, DOWN, SAME }

/**
 * Change from the previous period. [percent] is null when the previous value was 0 and the
 * current one is not (no meaningful percentage: only the arrow is shown).
 */
data class Change(val direction: Direction, val percent: Int?)

data class Insights(
    /** Day with the most snoring (only if any day had snoring). */
    val mostSnore: DayPoint?,
    /** Nights without talking / nights in range (only with 2 or more nights and at least one without talking). */
    val nightsWithoutTalking: Int?,
    val nightsInRange: Int,
    /** Day with the fewest sounds (only with 2 or more days that have nights). */
    val quietest: DayPoint?,
)

data class Trends(
    val days: Int,
    val series: List<DayPoint>,
    val current: PeriodStats,
    val previous: PeriodStats,
    /** null if the previous period has no nights (nothing to compare against). */
    val nightsChange: Change?,
    val snoreChange: Change?,
    val speechChange: Change?,
    val coughChange: Change?,
    val insights: Insights,
)

object TrendsModel {
    /**
     * Trends for the last [days] days ending on [today] (inclusive), compared with the previous
     * [days] days. Each night counts on the day it started; several on the same day are summed.
     * Snoring, speech and cough comparisons use the per-night average (not totals), so that
     * recording more nights does not look like "more snoring".
     */
    fun compute(nights: List<NightStats>, today: LocalDate, days: Int, zone: ZoneId = ZoneId.systemDefault()): Trends {
        require(days > 0)
        val start = today.minusDays(days - 1L)
        val prevStart = start.minusDays(days.toLong())
        val withDay = nights.map { it to Instant.ofEpochMilli(it.startedAtMs).atZone(zone).toLocalDate() }
        val inRange = withDay.filter { (_, d) -> d in start..today }
        val inPrevious = withDay.filter { (_, d) -> d >= prevStart && d < start }

        val byDay = inRange.groupBy({ it.second }, { it.first })
        val series = (0 until days).map { i ->
            val date = start.plusDays(i.toLong())
            val list = byDay[date].orEmpty()
            DayPoint(
                date = date,
                nights = list.size,
                snoreMs = list.sumOf { it.snoreMs },
                speech = list.sumOf { it.speech },
                cough = list.sumOf { it.cough },
                totalEvents = list.sumOf { it.totalEvents },
                latestSessionId = list.maxByOrNull { it.startedAtMs }?.sessionId,
            )
        }

        val current = stats(inRange.map { it.first })
        val previous = stats(inPrevious.map { it.first })
        val comparable = previous.nights > 0

        val withNights = series.filter { it.hasNights }
        val mostSnore = withNights.filter { it.snoreMs > 0 }.maxByOrNull { it.snoreMs }
        val withoutTalking = inRange.count { it.first.speech == 0 }
        val insights = Insights(
            mostSnore = mostSnore,
            nightsWithoutTalking = withoutTalking.takeIf { current.nights >= 2 && it > 0 },
            nightsInRange = current.nights,
            quietest = if (withNights.size >= 2) withNights.minByOrNull { it.totalEvents } else null,
        )

        return Trends(
            days = days,
            series = series,
            current = current,
            previous = previous,
            nightsChange = if (comparable) change(current.nights.toDouble(), previous.nights.toDouble()) else null,
            snoreChange = if (comparable) change(current.avgSnoreMs.toDouble(), previous.avgSnoreMs.toDouble()) else null,
            speechChange = if (comparable) change(current.avgSpeech, previous.avgSpeech) else null,
            coughChange = if (comparable) change(current.avgCough, previous.avgCough) else null,
            insights = insights,
        )
    }

    private fun stats(nights: List<NightStats>) = PeriodStats(
        nights = nights.size,
        totalSnoreMs = nights.sumOf { it.snoreMs },
        totalSpeech = nights.sumOf { it.speech },
        totalCough = nights.sumOf { it.cough },
    )

    /** Rounded percentage change; less than 1 % counts as the same. */
    fun change(current: Double, previous: Double): Change {
        if (previous == 0.0) return if (current == 0.0) Change(Direction.SAME, 0) else Change(Direction.UP, null)
        val pct = ((current - previous) / previous * 100).roundToInt()
        return when {
            abs(pct) < 1 -> Change(Direction.SAME, 0)
            pct > 0 -> Change(Direction.UP, pct)
            else -> Change(Direction.DOWN, -pct)
        }
    }

    /**
     * Series indices that get a label on the X axis. With 7 days or fewer, all of them; otherwise
     * the first, every 7th and the last, dropping the one before the last if it would be less than 4 days away.
     */
    fun labelIndices(days: Int): List<Int> {
        if (days <= 7) return (0 until days).toList()
        val idx = (0 until days step 7).toMutableList()
        val last = days - 1
        if (idx.last() != last) {
            if (last - idx.last() < 4) idx.removeAt(idx.lastIndex)
            idx.add(last)
        }
        return idx
    }
}
