package com.nochesonora.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class CalendarModelTest {
    @Test
    fun november2026StartsOnSundaySoMondayGridHasSixLeadingBlanks() {
        // November 1, 2026 is a Sunday.
        val cells = monthGrid(YearMonth.of(2026, 11), DayOfWeek.MONDAY)
        assertEquals(6, cells.takeWhile { it == null }.size)
        assertEquals(LocalDate.of(2026, 11, 1), cells[6])
        assertEquals(0, cells.size % 7)
        assertEquals(30, cells.count { it != null })
        assertEquals(42, cells.size) // 6 + 30 = 36 → 6 rows
    }

    @Test
    fun sundayFirstGridHasNoLeadingBlanksForNovember2026() {
        val cells = monthGrid(YearMonth.of(2026, 11), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 11, 1), cells[0])
        assertEquals(35, cells.size)
        assertNull(cells.last())
    }

    @Test
    fun february2027FitsInFourRowsWhenItStartsOnMonday() {
        // February 1, 2027 is a Monday and the month has 28 days.
        val cells = monthGrid(YearMonth.of(2027, 2), DayOfWeek.MONDAY)
        assertEquals(28, cells.size)
        assertEquals(LocalDate.of(2027, 2, 28), cells.last())
    }

    @Test
    fun sessionsAreGroupedByTheLocalDayTheyStarted() {
        val zone = ZoneId.of("America/Bogota")
        fun at(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone).toInstant().toEpochMilli()
        val starts = listOf(at(3, 23, 31), at(3, 21, 0), at(4, 0, 30), at(1, 22, 0))
        val byDay = groupByDay(starts, zone) { it }
        assertEquals(listOf(at(3, 23, 31), at(3, 21, 0)), byDay[LocalDate.of(2026, 11, 3)])
        assertEquals(listOf(at(4, 0, 30)), byDay[LocalDate.of(2026, 11, 4)])
        assertEquals(1, byDay[LocalDate.of(2026, 11, 1)]?.size)
        assertNull(byDay[LocalDate.of(2026, 11, 2)])
    }

    @Test
    fun firstDayOfWeekFollowsLanguage() {
        assertEquals(DayOfWeek.MONDAY, firstDayOfWeekFor("es"))
        assertEquals(DayOfWeek.SUNDAY, firstDayOfWeekFor("en"))
    }
}
