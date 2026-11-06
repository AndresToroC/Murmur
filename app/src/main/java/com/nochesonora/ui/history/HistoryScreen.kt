package com.nochesonora.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nochesonora.R
import com.nochesonora.data.db.SessionStatus
import com.nochesonora.data.db.SessionSummaryRow
import com.nochesonora.ui.common.BackIcon
import com.nochesonora.ui.common.ChevronRightIcon
import com.nochesonora.ui.common.ConfirmDialog
import com.nochesonora.ui.common.Dot
import com.nochesonora.ui.common.MurmurCard
import com.nochesonora.ui.common.ScreenHeader
import com.nochesonora.ui.common.SectionLabel
import com.nochesonora.ui.common.StarFilledIcon
import com.nochesonora.ui.common.StarIcon
import com.nochesonora.ui.common.StatusPill
import com.nochesonora.ui.common.appViewModel
import com.nochesonora.ui.common.formatDay
import com.nochesonora.ui.common.formatDurationShort
import com.nochesonora.ui.common.formatMonthYear
import com.nochesonora.ui.common.formatTime
import com.nochesonora.ui.common.label
import com.nochesonora.ui.theme.Border
import com.nochesonora.ui.theme.CoughRed
import com.nochesonora.ui.theme.Murmur
import com.nochesonora.ui.theme.OnSurface
import com.nochesonora.ui.theme.OnSurfaceMuted
import com.nochesonora.ui.theme.OnSurfaceSoft
import com.nochesonora.ui.theme.OtherGray
import com.nochesonora.ui.theme.Primary
import com.nochesonora.ui.theme.SnoreAmber
import com.nochesonora.ui.theme.SpeechBlue
import com.nochesonora.ui.theme.SurfaceHigh
import com.nochesonora.ui.theme.Track
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle as DateTextStyle

@Composable
fun HistoryScreen(onOpenSession: (Long) -> Unit) {
    val vm = appViewModel { HistoryViewModel(it) }
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<SessionSummaryRow?>(null) }

    HistoryContent(
        sessions = sessions,
        today = LocalDate.now(),
        onOpenSession = onOpenSession,
        onFavorite = vm::toggleFavorite,
        onDelete = { toDelete = it },
    )

    toDelete?.let { row ->
        ConfirmDialog(
            title = stringResource(R.string.history_delete_title),
            text = stringResource(R.string.history_delete_text),
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = {
                vm.delete(row.session.id)
                toDelete = null
            },
            onDismiss = { toDelete = null },
        )
    }
}

/**
 * History as a calendar: days with recordings get dots in the colors of what was heard;
 * tapping a day lists its nights below. No permissions or dialogs (used by the screenshot capture).
 */
@Composable
internal fun HistoryContent(
    sessions: List<SessionSummaryRow>?,
    today: LocalDate,
    onOpenSession: (Long) -> Unit,
    onFavorite: (SessionSummaryRow) -> Unit,
    onDelete: (SessionSummaryRow) -> Unit,
    initialTab: HistoryTab = HistoryTab.CALENDAR,
) {
    var tabName by rememberSaveable { mutableStateOf(initialTab.name) }
    val tab = HistoryTab.valueOf(tabName)
    val zone = remember { ZoneId.systemDefault() }
    val byDay = remember(sessions) { groupByDay(sessions.orEmpty(), zone) { it.session.startedAt } }
    val latestDay = sessions?.firstOrNull()?.let { sessionDay(it.session.startedAt, zone) }
    val earliestMonth = sessions?.lastOrNull()?.let { YearMonth.from(sessionDay(it.session.startedAt, zone)) }
    val currentMonth = YearMonth.from(today)

    // On open: the month and day of the most recent night (or today if there are none).
    var shownMonthKey by rememberSaveable(latestDay == null) { mutableStateOf(YearMonth.from(latestDay ?: today).toString()) }
    var selectedKey by rememberSaveable(latestDay == null) { mutableStateOf((latestDay ?: today).toString()) }
    val shownMonth = YearMonth.parse(shownMonthKey)
    val selected = LocalDate.parse(selectedKey)

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.history_title), Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp)) {
            sessions?.takeIf { it.isNotEmpty() }?.let {
                StatusPill(pluralStringResource(R.plurals.history_nights, it.size, it.size))
            }
        }
        if (sessions == null) return@Column
        HistoryTabs(
            selected = tab,
            onSelect = { tabName = it.name },
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        )
        if (tab == HistoryTab.TRENDS) {
            TrendsContent(rows = sessions, today = today, onOpenSession = onOpenSession)
            return@Column
        }

        val daySessions = byDay[selected].orEmpty()
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "calendar") {
                CalendarCard(
                    month = shownMonth,
                    today = today,
                    selected = selected,
                    byDay = byDay,
                    canGoBack = earliestMonth != null && shownMonth > earliestMonth,
                    canGoForward = shownMonth < currentMonth,
                    onMonthChange = { shownMonthKey = it.toString() },
                    onSelect = { selectedKey = it.toString() },
                )
            }
            item(key = "dayHeader") {
                Row(Modifier.padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(formatDay(selected.atStartOfDay(zone).toInstant().toEpochMilli()), Modifier.weight(1f))
                    if (daySessions.size > 1) {
                        Text(
                            pluralStringResource(R.plurals.history_nights, daySessions.size, daySessions.size),
                            style = Murmur.monoSmall,
                            color = OnSurfaceMuted,
                        )
                    }
                }
            }
            if (daySessions.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(if (sessions.isEmpty()) R.string.history_empty else R.string.history_day_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceMuted,
                    )
                }
            }
            items(daySessions, key = { it.session.id }) { row ->
                SessionCard(
                    row = row,
                    onClick = { onOpenSession(row.session.id) },
                    onFavorite = { onFavorite(row) },
                    onDelete = { onDelete(row) },
                )
            }
        }
    }
}

@Composable
private fun CalendarCard(
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate,
    byDay: Map<LocalDate, List<SessionSummaryRow>>,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onMonthChange: (YearMonth) -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val firstDay = firstDayOfWeekFor(locale.language)
    val cells = remember(month, firstDay) { monthGrid(month, firstDay) }
    val nightsInMonth = byDay.filterKeys { YearMonth.from(it) == month }.values.sumOf { it.size }

    MurmurCard(Modifier.fillMaxWidth(), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonthArrow(BackIcon, stringResource(R.string.history_prev_month), canGoBack) { onMonthChange(month.minusMonths(1)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    formatMonthYear(month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    if (nightsInMonth == 0) stringResource(R.string.history_month_empty)
                    else pluralStringResource(R.plurals.history_nights, nightsInMonth, nightsInMonth),
                    style = Murmur.monoSmall,
                    color = OnSurfaceMuted,
                )
            }
            MonthArrow(ChevronRightIcon, stringResource(R.string.history_next_month), canGoForward) { onMonthChange(month.plusMonths(1)) }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                val dow = firstDay.plus(i.toLong())
                Text(
                    dow.getDisplayName(DateTextStyle.NARROW, locale).uppercase(locale),
                    style = Murmur.monoTiny,
                    color = OnSurfaceMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(Modifier.weight(1f).height(48.dp).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (date != null) {
                            DayCell(
                                date = date,
                                rows = byDay[date].orEmpty(),
                                isToday = date == today,
                                isSelected = date == selected,
                                isFuture = date > today,
                                onClick = { onSelect(date) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthArrow(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(38.dp)
            .clip(shape)
            .border(1.dp, Border, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (enabled) OnSurface else Track, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    rows: List<SessionSummaryRow>,
    isToday: Boolean,
    isSelected: Boolean,
    isFuture: Boolean,
    onClick: () -> Unit,
) {
    val hasNights = rows.isNotEmpty()
    val shape = RoundedCornerShape(10.dp)
    var m = Modifier.fillMaxSize().clip(shape)
    if (isSelected) m = m.background(SurfaceHigh).border(1.dp, Primary, shape)
    else if (isToday) m = m.border(1.dp, Border, shape)
    m = m.clickable(enabled = !isFuture, onClick = onClick)
    Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            "${date.dayOfMonth}",
            style = Murmur.monoBody.copy(
                fontSize = 14.sp,
                fontWeight = if (hasNights) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = when {
                isFuture -> Track
                hasNights || isSelected -> OnSurface
                else -> OnSurfaceMuted
            },
        )
        Spacer(Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(5.dp)) {
            dayDots(rows).forEach { Dot(it, 5.dp) }
        }
    }
}

/** Up to 3 dots for what was heard that day; a night with no events gets one accent-colored dot. */
private fun dayDots(rows: List<SessionSummaryRow>): List<Color> {
    if (rows.isEmpty()) return emptyList()
    val dots = buildList {
        if (rows.any { it.snoreMs > 0 || it.snoreEvents > 0 }) add(SnoreAmber)
        if (rows.any { it.speechEvents > 0 }) add(SpeechBlue)
        if (rows.any { it.coughEvents > 0 }) add(CoughRed)
        if (rows.any { it.otherEvents > 0 }) add(OtherGray)
    }
    return dots.take(3).ifEmpty { listOf(Primary) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionCard(row: SessionSummaryRow, onClick: () -> Unit, onFavorite: () -> Unit, onDelete: () -> Unit) {
    val s = row.session
    var menu by remember { mutableStateOf(false) }
    MurmurCard(Modifier.fillMaxWidth(), radius = 14.dp, onClick = onClick, padding = 0.dp) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 14.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val end = s.endedAt?.let { " → ${formatTime(it)}" } ?: ""
                    Text("${formatTime(s.startedAt)}$end", style = MaterialTheme.typography.titleMedium)
                    if (s.status != SessionStatus.COMPLETED) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            s.status.label(),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (s.status == SessionStatus.IN_PROGRESS) Primary else CoughRed,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                val duration = (s.endedAt ?: s.lastHeartbeatAt) - s.startedAt
                Text(
                    stringResource(R.string.history_duration, formatDurationShort(duration)),
                    style = Murmur.monoBody,
                    color = OnSurfaceMuted,
                )
                Spacer(Modifier.height(10.dp))
                val snore = stringResource(R.string.history_count_snore)
                val speech = stringResource(R.string.history_count_speech)
                val cough = stringResource(R.string.history_count_cough)
                val other = stringResource(R.string.history_count_other)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val parts = buildList {
                        if (row.snoreMs > 0) add(Triple(SnoreAmber, formatDurationShort(row.snoreMs), snore))
                        if (row.speechEvents > 0) add(Triple(SpeechBlue, "${row.speechEvents}×", speech))
                        if (row.coughEvents > 0) add(Triple(CoughRed, "${row.coughEvents}×", cough))
                        if (row.otherEvents > 0) add(Triple(OtherGray, "${row.otherEvents}×", other))
                    }
                    if (parts.isEmpty()) {
                        Text(stringResource(R.string.history_no_events), style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
                    }
                    parts.forEach { (color, value, name) -> Count(color, value, name) }
                }
            }
            IconButton(onClick = onFavorite) {
                if (s.isFavorite) {
                    Icon(StarFilledIcon, stringResource(R.string.common_unfavorite), tint = SnoreAmber, modifier = Modifier.size(20.dp))
                } else {
                    Icon(StarIcon, stringResource(R.string.common_favorite), tint = OnSurfaceMuted, modifier = Modifier.size(20.dp))
                }
            }
            if (s.status != SessionStatus.IN_PROGRESS) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.history_more), tint = OnSurfaceMuted)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, onClick = {
                            menu = false
                            onDelete()
                        })
                    }
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun Count(color: Color, value: String, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color, 8.dp)
        Spacer(Modifier.width(6.dp))
        Text(value, style = Murmur.monoBody, color = OnSurfaceSoft)
        Spacer(Modifier.width(4.dp))
        Text(name, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
    }
}
