package com.nochesonora.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class TrendsModelTest {
    private val zone = ZoneId.of("America/Bogota")
    private val today = LocalDate.of(2026, 11, 4)

    private fun at(month: Int, day: Int, h: Int, m: Int = 0) =
        ZonedDateTime.of(2026, month, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private fun night(id: Long, startedAt: Long, snoreMin: Int = 0, snoreEvents: Int = 0, speech: Int = 0, cough: Int = 0, other: Int = 0) =
        NightStats(id, startedAt, snoreMin * 60_000L, snoreEvents, speech, cough, other)

    @Test
    fun sevenDaySeriesEndsTodayAndMarksEmptyDays() {
        val t = TrendsModel.compute(listOf(night(1, at(11, 3, 23, 31), speech = 8, other = 9)), today, 7, zone)
        assertEquals(7, t.series.size)
        assertEquals(LocalDate.of(2026, 10, 29), t.series.first().date)
        assertEquals(today, t.series.last().date)
        assertEquals(1, t.series.count { it.hasNights })
        val nov3 = t.series.single { it.hasNights }
        assertEquals(LocalDate.of(2026, 11, 3), nov3.date)
        assertEquals(8, nov3.speech)
        assertEquals(17, nov3.totalEvents)
        assertEquals(1L, nov3.latestSessionId)
    }

    @Test
    fun twoNightsSameDayAreSummedAndLatestOneIsOpened() {
        val nights = listOf(
            night(1, at(11, 2, 21), snoreMin = 10, snoreEvents = 2, speech = 1),
            night(2, at(11, 2, 23, 30), snoreMin = 32, snoreEvents = 5, cough = 2),
        )
        val day = TrendsModel.compute(nights, today, 7, zone).series.single { it.hasNights }
        assertEquals(2, day.nights)
        assertEquals(42 * 60_000L, day.snoreMs)
        assertEquals(1, day.speech)
        assertEquals(2, day.cough)
        assertEquals(10, day.totalEvents)
        assertEquals(2L, day.latestSessionId)
    }

    @Test
    fun averagesArePerNightNotPerDay() {
        val nights = listOf(
            night(1, at(11, 1, 23), snoreMin = 30, speech = 2, cough = 1),
            night(2, at(11, 2, 23), snoreMin = 10, speech = 0, cough = 0),
            night(3, at(11, 3, 23), snoreMin = 20, speech = 1, cough = 3),
        )
        val c = TrendsModel.compute(nights, today, 7, zone).current
        assertEquals(3, c.nights)
        assertEquals(20 * 60_000L, c.avgSnoreMs)
        assertEquals(1.0, c.avgSpeech, 1e-9)
        assertEquals(4, c.totalCough)
    }

    @Test
    fun noComparisonWhenPreviousPeriodIsEmpty() {
        val t = TrendsModel.compute(listOf(night(1, at(11, 3, 23), snoreMin = 5)), today, 7, zone)
        assertEquals(0, t.previous.nights)
        assertNull(t.nightsChange)
        assertNull(t.snoreChange)
        assertNull(t.speechChange)
        assertNull(t.coughChange)
    }

    @Test
    fun comparesAveragesWithThePreviousSevenDays() {
        val nights = listOf(
            // Periodo actual (29 oct – 4 nov): 2 noches, 30 min de media, 1 vez hablando de media.
            night(1, at(11, 3, 23), snoreMin = 20, speech = 2),
            night(2, at(11, 1, 23), snoreMin = 40, speech = 0),
            // Periodo anterior (22 – 28 oct): 1 noche, 40 min, 1 vez hablando, 2 toses.
            night(3, at(10, 25, 23), snoreMin = 40, speech = 1, cough = 2),
            // Fuera de los dos periodos: no cuenta.
            night(4, at(10, 10, 23), snoreMin = 300),
        )
        val t = TrendsModel.compute(nights, today, 7, zone)
        assertEquals(1, t.previous.nights)
        assertEquals(Change(Direction.UP, 100), t.nightsChange)
        assertEquals(Change(Direction.DOWN, 25), t.snoreChange)
        assertEquals(Change(Direction.SAME, 0), t.speechChange)
        assertEquals(Change(Direction.DOWN, 100), t.coughChange)
    }

    @Test
    fun thirtyDayRangeCrossesTheMonthBoundary() {
        val nights = listOf(
            night(1, at(10, 6, 23), speech = 3), // primer día del rango
            night(2, at(10, 5, 23), speech = 9), // justo antes: periodo anterior
            night(3, at(11, 4, 1), cough = 1), // hoy de madrugada
        )
        val t = TrendsModel.compute(nights, today, 30, zone)
        assertEquals(30, t.series.size)
        assertEquals(LocalDate.of(2026, 10, 6), t.series.first().date)
        assertEquals(2, t.current.nights)
        assertEquals(1, t.previous.nights)
        assertEquals(3, t.series.first().speech)
        assertEquals(1, t.series.last().cough)
    }

    @Test
    fun changeHandlesZeroPreviousAndSmallDifferences() {
        assertEquals(Change(Direction.SAME, 0), TrendsModel.change(0.0, 0.0))
        assertEquals(Change(Direction.UP, null), TrendsModel.change(2.0, 0.0))
        assertEquals(Change(Direction.DOWN, 100), TrendsModel.change(0.0, 3.0))
        assertEquals(Change(Direction.SAME, 0), TrendsModel.change(100.4, 100.0))
        assertEquals(Change(Direction.UP, 20), TrendsModel.change(1.2, 1.0))
        assertEquals(Change(Direction.DOWN, 15), TrendsModel.change(0.85, 1.0))
    }

    @Test
    fun insightsOnlyAppearWhenTheyApply() {
        // Una sola noche sin ronquido: ningún destacado.
        val one = TrendsModel.compute(listOf(night(1, at(11, 3, 23), speech = 2)), today, 7, zone).insights
        assertNull(one.mostSnore)
        assertNull(one.nightsWithoutTalking)
        assertNull(one.quietest)

        val nights = listOf(
            night(1, at(11, 1, 23), snoreMin = 12, snoreEvents = 3, speech = 0, other = 1),
            night(2, at(11, 2, 23), snoreMin = 42, snoreEvents = 9, speech = 3),
            night(3, at(11, 3, 23), snoreMin = 0, speech = 0, other = 1),
        )
        val ins = TrendsModel.compute(nights, today, 7, zone).insights
        assertEquals(LocalDate.of(2026, 11, 2), ins.mostSnore?.date)
        assertEquals(2, ins.nightsWithoutTalking)
        assertEquals(3, ins.nightsInRange)
        assertEquals(LocalDate.of(2026, 11, 3), ins.quietest?.date)
    }

    @Test
    fun allNightsWithTalkingGivesNoWithoutTalkingInsight() {
        val nights = listOf(night(1, at(11, 1, 23), speech = 1), night(2, at(11, 2, 23), speech = 4))
        assertNull(TrendsModel.compute(nights, today, 7, zone).insights.nightsWithoutTalking)
    }

    @Test
    fun labelIndicesAvoidCollisions() {
        assertEquals((0 until 7).toList(), TrendsModel.labelIndices(7))
        assertEquals(listOf(0, 7, 14, 21, 29), TrendsModel.labelIndices(30))
        // 31 días: 28 queda a 2 del último (30), así que se quita.
        assertEquals(listOf(0, 7, 14, 21, 30), TrendsModel.labelIndices(31))
        val idx = TrendsModel.labelIndices(30)
        assertTrue(idx.zipWithNext().all { (a, b) -> b - a >= 4 })
        assertFalse(idx.isEmpty())
    }
}
