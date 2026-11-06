package com.nochesonora.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.nochesonora.core.classification.Category
import com.nochesonora.data.db.EpisodeEntity
import com.nochesonora.data.db.PauseEntity
import com.nochesonora.ui.common.formatTime
import com.nochesonora.ui.theme.Border
import com.nochesonora.ui.theme.Murmur
import com.nochesonora.ui.theme.OnSurfaceMuted
import com.nochesonora.ui.theme.PauseGray
import com.nochesonora.ui.theme.Surface
import com.nochesonora.ui.theme.color
import java.util.Calendar
import kotlin.math.abs

/**
 * Timeline of the night: each episode is a vertical bar in its color (taller for speech or
 * snoring, and wider the longer it lasts), pauses shaded gray in the background, and below it the
 * start time, the full hours and the end time. Tapping a bar calls [onEpisodeTap].
 */
@Composable
fun Timeline(
    startMs: Long,
    endMs: Long,
    episodes: List<EpisodeEntity>,
    pauses: List<PauseEntity>,
    onEpisodeTap: (EpisodeEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val span = (endMs - startMs).coerceAtLeast(60_000L)
    val measurer = rememberTextMeasurer()
    val labelStyle = Murmur.monoTiny.copy(color = OnSurfaceMuted)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(94.dp)
            .pointerInput(episodes, startMs, span) {
                detectTapGestures { pos ->
                    val inset = 8.dp.toPx()
                    val width = size.width - inset * 2
                    fun x(ms: Long) = inset + (ms - startMs).toFloat() / span * width
                    val hit = episodes.minByOrNull { ep ->
                        val x0 = x(ep.startedAt)
                        val x1 = x(ep.endedAt)
                        when {
                            pos.x < x0 -> x0 - pos.x
                            pos.x > x1 -> pos.x - x1
                            else -> 0f
                        }
                    }
                    if (hit != null) {
                        val x0 = x(hit.startedAt)
                        val x1 = x(hit.endedAt)
                        val dist = if (pos.x in x0..x1) 0f else minOf(abs(pos.x - x0), abs(pos.x - x1))
                        if (dist <= 12.dp.toPx()) onEpisodeTap(hit)
                    }
                }
            },
    ) {
        val cardH = 72.dp.toPx()
        val radius = CornerRadius(12.dp.toPx())
        val inset = 8.dp.toPx()
        val width = size.width - inset * 2
        fun x(ms: Long) = (inset + (ms - startMs).toFloat() / span * width).coerceIn(inset, inset + width)

        drawRoundRect(Surface, size = Size(size.width, cardH), cornerRadius = radius)

        for (p in pauses) {
            val x0 = x(p.startedAt)
            val x1 = x(p.endedAt ?: endMs)
            drawRect(PauseGray.copy(alpha = 0.5f), Offset(x0, 1.dp.toPx()), Size((x1 - x0).coerceAtLeast(2.dp.toPx()), cardH - 2.dp.toPx()))
        }

        val bottom = cardH - 10.dp.toPx()
        val minWidth = 3.dp.toPx()
        episodes.forEachIndexed { i, ep ->
            val h = when (ep.category) {
                Category.SPEECH -> 34 + (i % 3) * 8
                Category.SNORING -> 48
                Category.COUGH -> 40
                else -> 22
            }.dp.toPx()
            val x0 = x(ep.startedAt)
            val w = (x(ep.endedAt) - x0).coerceAtLeast(minWidth)
            drawRoundRect(ep.category.color(), Offset(x0, bottom - h), Size(w, h), CornerRadius(2.dp.toPx()))
        }

        drawRoundRect(Border, size = Size(size.width, cardH), cornerRadius = radius, style = Stroke(1.dp.toPx()))

        // Labels: start, full hours and end, without overlapping.
        val labelTop = cardH + 6.dp.toPx()
        val start = measurer.measure(formatTime(startMs), labelStyle)
        val end = measurer.measure(formatTime(endMs), labelStyle)
        drawText(start, topLeft = Offset(0f, labelTop))
        val endX = size.width - end.size.width
        drawText(end, topLeft = Offset(endX, labelTop))
        var lastLabelEnd = start.size.width.toFloat()
        val cal = Calendar.getInstance().apply {
            timeInMillis = startMs
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.HOUR_OF_DAY, 1)
        }
        val gap = 10.dp.toPx()
        while (cal.timeInMillis < endMs) {
            val tx = x(cal.timeInMillis)
            val layout = measurer.measure(formatTime(cal.timeInMillis), labelStyle)
            val lx = tx - layout.size.width / 2f
            if (lx > lastLabelEnd + gap && lx + layout.size.width < endX - gap) {
                drawText(layout, topLeft = Offset(lx, labelTop))
                lastLabelEnd = lx + layout.size.width
            }
            cal.add(Calendar.HOUR_OF_DAY, 1)
        }
    }
}
