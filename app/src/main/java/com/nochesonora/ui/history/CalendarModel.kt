package com.nochesonora.ui.history

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Month cells in rows of 7, starting on [firstDayOfWeek]. `null` = blank before day 1
 * or after the last day. Rows are always complete (4 to 6).
 */
fun monthGrid(month: YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate?> {
    val first = month.atDay(1)
    val leading = (first.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val cells = ArrayList<LocalDate?>(42)
    repeat(leading) { cells.add(null) }
    for (d in 1..month.lengthOfMonth()) cells.add(month.atDay(d))
    while (cells.size % 7 != 0) cells.add(null)
    return cells
}

/** Local day a session belongs to: the day it started. */
fun sessionDay(startedAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(startedAtMs).atZone(zone).toLocalDate()

/** Groups by start day, keeping the input order within each day. */
fun <T> groupByDay(items: List<T>, zone: ZoneId = ZoneId.systemDefault(), startedAt: (T) -> Long): Map<LocalDate, List<T>> =
    items.groupBy { sessionDay(startedAt(it), zone) }

/** First day of the week: Monday in Spanish, Sunday in English (US). */
fun firstDayOfWeekFor(language: String): DayOfWeek = if (language == "en") DayOfWeek.SUNDAY else DayOfWeek.MONDAY
