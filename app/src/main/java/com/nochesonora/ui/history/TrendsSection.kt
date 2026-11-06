package com.nochesonora.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nochesonora.R
import com.nochesonora.data.db.SessionSummaryRow
import com.nochesonora.ui.common.Dot
import com.nochesonora.ui.common.MurmurCard
import com.nochesonora.ui.common.SectionLabel
import com.nochesonora.ui.common.TileRow
import com.nochesonora.ui.common.formatDay
import com.nochesonora.ui.common.formatDurationShort
import com.nochesonora.ui.theme.CoughRed
import com.nochesonora.ui.theme.Murmur
import com.nochesonora.ui.theme.OnSurface
import com.nochesonora.ui.theme.OnSurfaceMuted
import com.nochesonora.ui.theme.OnSurfaceSoft
import com.nochesonora.ui.theme.Primary
import com.nochesonora.ui.theme.SnoreAmber
import com.nochesonora.ui.theme.SpeechBlue
import com.nochesonora.ui.theme.Track
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DateTextStyle
import kotlin.math.roundToInt

/** History tabs. */
enum class HistoryTab { CALENDAR, TRENDS }

/** "Calendar | Trends" selector below the History header. */
@Composable
internal fun HistoryTabs(selected: HistoryTab, onSelect: (HistoryTab) -> Unit, modifier: Modifier = Modifier) {
    val tabs = listOf(
        HistoryTab.CALENDAR to stringResource(R.string.trends_tab_calendar),
        HistoryTab.TRENDS to stringResource(R.string.trends_tab_trends),
    )
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        tabs.forEachIndexed { i, (tab, label) ->
            SegmentedButton(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                shape = SegmentedButtonDefaults.itemShape(i, tabs.size),
            ) { Text(label) }
        }
    }
}

/**
 * Trends for the last 7 or 30 days: tiles with averages and comparison with the previous
 * period, a bar chart per category and the highlights. Scrolls on its own.
 */
@Composable
internal fun TrendsContent(rows: List<SessionSummaryRow>, today: LocalDate, onOpenSession: (Long) -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    var days by rememberSaveable { mutableIntStateOf(7) }
    val nights = remember(rows) {
        rows.map {
            NightStats(
                sessionId = it.session.id,
                startedAtMs = it.session.startedAt,
                snoreMs = it.snoreMs,
                snoreEvents = it.snoreEvents,
                speech = it.speechEvents,
                cough = it.coughEvents,
                other = it.otherEvents,
            )
        }
    }
    val trends = remember(nights, today, days) { TrendsModel.compute(nights, today, days, zone) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val ranges = listOf(7 to stringResource(R.string.trends_range_7), 30 to stringResource(R.string.trends_range_30))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ranges.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = days == value,
                    onClick = { days = value },
                    shape = SegmentedButtonDefaults.itemShape(i, ranges.size),
                ) { Text(label) }
            }
        }

        if (trends.current.nights == 0) {
            Text(
                stringResource(R.string.trends_empty, days),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }

        SummaryTiles(trends)

        BarChartCard(
            title = stringResource(R.string.trends_chart_snore),
            series = trends.series,
            color = SnoreAmber,
            value = { it.snoreMs / 60_000f },
            maxLabel = { stringResource(R.string.trends_max_minutes, it.roundToInt()) },
            onOpenSession = onOpenSession,
        )
        BarChartCard(
            title = stringResource(R.string.trends_chart_speech),
            series = trends.series,
            color = SpeechBlue,
            value = { it.speech.toFloat() },
            maxLabel = { stringResource(R.string.trends_max_times, it.roundToInt()) },
            onOpenSession = onOpenSession,
        )
        BarChartCard(
            title = stringResource(R.string.trends_chart_cough),
            series = trends.series,
            color = CoughRed,
            value = { it.cough.toFloat() },
            maxLabel = { stringResource(R.string.trends_max_times, it.roundToInt()) },
            onOpenSession = onOpenSession,
        )
        Text(stringResource(R.string.trends_tap_hint), style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)

        InsightsCard(trends.insights, zone)
    }
}

@Composable
private fun SummaryTiles(trends: Trends) {
    val locale = LocalConfiguration.current.locales[0]
    val c = trends.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TileRow {
            TrendTile(
                value = "${c.nights}",
                label = stringResource(R.string.trends_tile_nights),
                change = trends.nightsChange,
                goodWhenDown = null,
                dot = Primary,
                modifier = Modifier.weight(1f),
            )
            TrendTile(
                value = if (c.avgSnoreMs == 0L) "0m" else formatDurationShort(c.avgSnoreMs),
                label = stringResource(R.string.trends_tile_snore),
                change = trends.snoreChange,
                goodWhenDown = true,
                dot = SnoreAmber,
                modifier = Modifier.weight(1f),
            )
        }
        TileRow {
            TrendTile(
                value = String.format(locale, "%.1f", c.avgSpeech),
                label = stringResource(R.string.trends_tile_speech),
                change = trends.speechChange,
                goodWhenDown = true,
                dot = SpeechBlue,
                modifier = Modifier.weight(1f),
            )
            TrendTile(
                value = "${c.totalCough}",
                label = stringResource(R.string.trends_tile_cough),
                change = trends.coughChange,
                goodWhenDown = true,
                dot = CoughRed,
                modifier = Modifier.weight(1f),
            )
        }
        if (trends.previous.nights > 0) {
            Text(
                stringResource(R.string.trends_vs_previous, trends.days),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
            )
        }
    }
}

/**
 * Tile with the figure for the period and, below it, the change from the previous one.
 * [goodWhenDown]: true = going down is good (teal), going up is bad (red); null = neutral.
 */
@Composable
private fun TrendTile(value: String, label: String, change: Change?, goodWhenDown: Boolean?, dot: Color, modifier: Modifier = Modifier) {
    MurmurCard(modifier, radius = 12.dp, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(dot, 8.dp)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Text(value, style = Murmur.stat.copy(fontSize = 20.sp), color = OnSurface, maxLines = 1)
        if (change != null) {
            val text = when (change.direction) {
                Direction.SAME -> "="
                Direction.UP -> "↑" + (change.percent?.let { " " + stringResource(R.string.trends_percent, it) } ?: "")
                Direction.DOWN -> "↓" + (change.percent?.let { " " + stringResource(R.string.trends_percent, it) } ?: "")
            }
            val color = when {
                goodWhenDown == null || change.direction == Direction.SAME -> OnSurfaceMuted
                change.direction == Direction.DOWN -> Primary
                else -> CoughRed
            }
            Text(text, style = Murmur.monoTiny, color = color, maxLines = 1)
        }
    }
}

/**
 * Bars per day. A day without nights gets a faint mark; one with nights but none of that sound,
 * a short stroke in the color. Tapping the bar of a day with nights opens that day's most recent one.
 */
@Composable
private fun BarChartCard(
    title: String,
    series: List<DayPoint>,
    color: Color,
    value: (DayPoint) -> Float,
    maxLabel: @Composable (Float) -> String,
    onOpenSession: (Long) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val values = series.map(value)
    val max = values.maxOrNull() ?: 0f
    val open by rememberUpdatedState(onOpenSession)
    val measurer = rememberTextMeasurer()
    val labelStyle = Murmur.monoTiny.copy(color = OnSurfaceMuted, fontSize = 10.sp)
    val shortFormat = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
    val labelIdx = remember(series.size) { TrendsModel.labelIndices(series.size).toSet() }
    val labels = series.mapIndexed { i, d ->
        when {
            i !in labelIdx -> null
            series.size <= 7 -> d.date.dayOfWeek.getDisplayName(DateTextStyle.NARROW, locale).uppercase(locale) + "\n" + d.date.dayOfMonth
            else -> shortFormat.format(d.date).replace(".", "")
        }
    }

    MurmurCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = OnSurfaceMuted, modifier = Modifier.weight(1f))
            if (max > 0f) Text(maxLabel(max), style = Murmur.monoSmall, color = OnSurfaceSoft)
        }
        Spacer(Modifier.height(12.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(if (series.size <= 7) 136.dp else 124.dp)
                .pointerInput(series) {
                    detectTapGestures { o ->
                        if (series.isEmpty()) return@detectTapGestures
                        val i = (o.x / (size.width.toFloat() / series.size)).toInt().coerceIn(0, series.lastIndex)
                        series[i].latestSessionId?.let(open)
                    }
                },
        ) {
            val n = series.size
            if (n == 0) return@Canvas
            val labelArea = if (n <= 7) 30.dp.toPx() else 18.dp.toPx()
            val chartH = size.height - labelArea
            val slot = size.width / n
            val barW = (slot * 0.62f).coerceIn(2.dp.toPx(), 18.dp.toPx())
            val radius = CornerRadius(minOf(barW / 2, 3.dp.toPx()))
            series.forEachIndexed { i, d ->
                val x = i * slot + (slot - barW) / 2
                val v = values[i]
                when {
                    !d.hasNights -> {
                        val h = 2.dp.toPx()
                        drawRoundRect(Track, Offset(x, chartH - h), Size(barW, h), radius)
                    }
                    v <= 0f || max <= 0f -> {
                        val h = 3.dp.toPx()
                        drawRoundRect(color.copy(alpha = 0.45f), Offset(x, chartH - h), Size(barW, h), radius)
                    }
                    else -> {
                        val h = maxOf(3.dp.toPx(), v / max * (chartH - 4.dp.toPx()))
                        drawRoundRect(color, Offset(x, chartH - h), Size(barW, h), radius)
                    }
                }
            }
            labels.forEachIndexed { i, text ->
                if (text == null) return@forEachIndexed
                val layout = measurer.measure(text, labelStyle)
                val cx = i * slot + slot / 2
                val left = (cx - layout.size.width / 2f).coerceIn(0f, maxOf(0f, size.width - layout.size.width))
                drawText(layout, topLeft = Offset(left, chartH + 6.dp.toPx()))
            }
        }
    }
}

@Composable
private fun InsightsCard(insights: Insights, zone: ZoneId) {
    fun day(d: LocalDate) = formatDay(d.atStartOfDay(zone).toInstant().toEpochMilli())
    val lines = buildList {
        insights.mostSnore?.let {
            add(SnoreAmber to stringResource(R.string.trends_insight_most_snore, day(it.date), formatDurationShort(it.snoreMs)))
        }
        insights.nightsWithoutTalking?.let {
            add(SpeechBlue to stringResource(R.string.trends_insight_no_talk, it, insights.nightsInRange))
        }
        insights.quietest?.let {
            val sounds = pluralStringResource(R.plurals.trends_sounds, it.totalEvents, it.totalEvents)
            add(Primary to stringResource(R.string.trends_insight_quietest, day(it.date), sounds))
        }
    }
    if (lines.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        SectionLabel(stringResource(R.string.trends_insights))
        MurmurCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                lines.forEach { (c, text) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(c, 6.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = OnSurface)
                    }
                }
            }
        }
    }
}
