package com.nochesonora.ui.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** Lo que interesa de una noche para las tendencias (independiente de Room y de Android). */
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

/** Un día del rango: las noches que empezaron ese día, sumadas. */
data class DayPoint(
    val date: LocalDate,
    val nights: Int,
    val snoreMs: Long,
    val speech: Int,
    val cough: Int,
    val totalEvents: Int,
    /** La noche que empezó más tarde ese día (la que se abre al tocar la barra). */
    val latestSessionId: Long?,
) {
    val hasNights: Boolean get() = nights > 0
}

/** Totales y medias por noche de un periodo. */
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
 * Cambio respecto al periodo anterior. [percent] es null cuando el anterior era 0 y el actual
 * no (no hay porcentaje con sentido: se muestra solo la flecha).
 */
data class Change(val direction: Direction, val percent: Int?)

data class Insights(
    /** Día con más ronquido (solo si alguno tuvo ronquido). */
    val mostSnore: DayPoint?,
    /** Noches sin hablar / noches del rango (solo con 2 o más noches y al menos una sin hablar). */
    val nightsWithoutTalking: Int?,
    val nightsInRange: Int,
    /** Día con menos sonidos (solo con 2 o más días con noches). */
    val quietest: DayPoint?,
)

data class Trends(
    val days: Int,
    val series: List<DayPoint>,
    val current: PeriodStats,
    val previous: PeriodStats,
    /** null si el periodo anterior no tiene noches (no hay con qué comparar). */
    val nightsChange: Change?,
    val snoreChange: Change?,
    val speechChange: Change?,
    val coughChange: Change?,
    val insights: Insights,
)

object TrendsModel {
    /**
     * Tendencias de los últimos [days] días terminando en [today] (incluido), comparadas con los
     * [days] días anteriores. Cada noche cuenta en el día en que empezó; varias el mismo día se suman.
     * Las comparaciones de ronquido, habla y tos usan la media por noche (no los totales), para
     * que grabar más noches no parezca "más ronquido".
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

    /** Cambio porcentual redondeado; menos de 1 % cuenta como igual. */
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
     * Índices de la serie que llevan etiqueta en el eje X. Con 7 días o menos, todos; si no, el
     * primero, cada 7 y el último, quitando el anterior al último si quedaría a menos de 4 días.
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
