package com.nochesonora.ui.history

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Celdas del mes en filas de 7, empezando por [firstDayOfWeek]. `null` = hueco antes del día 1
 * o después del último. Siempre son filas completas (4 a 6).
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

/** Día (local) al que pertenece una sesión: el día en que empezó. */
fun sessionDay(startedAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(startedAtMs).atZone(zone).toLocalDate()

/** Agrupa por día de inicio, conservando el orden de entrada dentro de cada día. */
fun <T> groupByDay(items: List<T>, zone: ZoneId = ZoneId.systemDefault(), startedAt: (T) -> Long): Map<LocalDate, List<T>> =
    items.groupBy { sessionDay(startedAt(it), zone) }

/** Primer día de la semana: lunes en español, domingo en inglés (EE. UU.). */
fun firstDayOfWeekFor(language: String): DayOfWeek = if (language == "en") DayOfWeek.SUNDAY else DayOfWeek.MONDAY
