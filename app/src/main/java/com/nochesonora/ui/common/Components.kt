package com.nochesonora.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nochesonora.R
import com.nochesonora.core.classification.Category
import com.nochesonora.ui.theme.Border
import com.nochesonora.ui.theme.BorderStrong
import com.nochesonora.ui.theme.CoughRed
import com.nochesonora.ui.theme.Murmur
import com.nochesonora.ui.theme.OnPrimary
import com.nochesonora.ui.theme.OnSurface
import com.nochesonora.ui.theme.OnSurfaceMuted
import com.nochesonora.ui.theme.OnSurfaceSoft
import com.nochesonora.ui.theme.Primary
import com.nochesonora.ui.theme.SnoreAmber
import com.nochesonora.ui.theme.Surface
import com.nochesonora.ui.theme.SurfaceHigh
import com.nochesonora.ui.theme.Track
import com.nochesonora.ui.theme.color

/** Ícono de pausa (no está en material-icons-core). */
val PauseIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Pause") {
        materialPath {
            moveTo(6f, 19f); horizontalLineToRelative(4f); verticalLineTo(5f); horizontalLineTo(6f); close()
            moveTo(14f, 5f); verticalLineToRelative(14f); horizontalLineToRelative(4f); verticalLineTo(5f); close()
        }
    }
}

/** Ícono de trazo a partir de un path SVG de 24×24. */
fun strokeIcon(name: String, path: String, strokeWidth: Float = 1.8f, filled: Boolean = false): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(path),
        fill = if (filled) SolidColor(Color.Black) else null,
        stroke = SolidColor(Color.Black),
        strokeLineWidth = strokeWidth,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()

/** Logo "Luna de barras": luna creciente hecha de barras de nivel (versión pequeña, la misma del botón de ajustes rápidos). */
val BarsIcon: ImageVector by lazy {
    strokeIcon("Logo", "M5.2,9.76V14.24M8.8,5.16V18.84M12.4,3.67V5.12M12.4,16.48V20.33M16,19.45V20.33", strokeWidth = 2.2f)
}
private const val STAR = "M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z"
val StarIcon: ImageVector by lazy { strokeIcon("Star", STAR) }
val StarFilledIcon: ImageVector by lazy { strokeIcon("StarFilled", STAR, filled = true) }
val BackIcon: ImageVector by lazy { strokeIcon("Back", "M15 18l-6-6 6-6", strokeWidth = 2f) }
val ChevronDownIcon: ImageVector by lazy { strokeIcon("ChevronDown", "M6 9l6 6 6-6", strokeWidth = 2f) }
val ChevronUpIcon: ImageVector by lazy { strokeIcon("ChevronUp", "M6 15l6-6 6 6", strokeWidth = 2f) }
val ChevronRightIcon: ImageVector by lazy { strokeIcon("ChevronRight", "M9 6l6 6-6 6", strokeWidth = 2f) }

/** Tarjeta del diseño: superficie azul noche con borde fino. */
@Composable
fun MurmurCard(
    modifier: Modifier = Modifier,
    radius: Dp = 16.dp,
    borderColor: Color = Border,
    onClick: (() -> Unit)? = null,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .clip(shape)
            .background(Surface)
            .border(1.dp, borderColor, shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(padding),
        content = content,
    )
}

/** Título de sección en mayúsculas y gris. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier, style = Murmur.section, color = OnSurfaceMuted)
}

/** Casilla con punto de color, cifra monoespaciada y etiqueta. */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    valueColor: Color = OnSurface,
    labelFirst: Boolean = false,
) {
    MurmurCard(modifier, radius = 12.dp, padding = 12.dp) {
        if (labelFirst) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(value, style = Murmur.stat.copy(fontSize = 20.sp), color = valueColor, maxLines = 1)
        } else {
            dot?.let { Dot(it, 8.dp) }
            Spacer(Modifier.height(4.dp))
            Text(value, style = Murmur.stat, color = valueColor, maxLines = 1)
            Text(label, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun CategoryDot(category: Category, modifier: Modifier = Modifier) = Dot(category.color(), 8.dp, modifier)

/** Píldora con borde ("● GRABANDO"). */
@Composable
fun StatusPill(text: String, color: Color = OnSurfaceMuted, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .border(1.dp, Color(0xFF243049), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = Murmur.monoSmall,
        color = color,
    )
}

/** Botón cuadrado de 44 dp con borde (volver, favorita). */
@Composable
fun SquareIconButton(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = OnSurface) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .size(44.dp)
            .clip(shape)
            .background(Surface)
            .border(1.dp, Border, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** Botón principal relleno con el acento. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
    ) { Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 17.sp)) }
}

/** Botón secundario: superficie oscura con borde (p. ej. "Detener"). */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 56.dp, textColor: Color = OnSurface) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(if (height < 48.dp) 10.dp else 14.dp),
        border = BorderStroke(1.dp, BorderStrong),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = SurfaceHigh, contentColor = textColor),
    ) {
        Text(
            text,
            style = if (height < 48.dp) MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = OnSurfaceSoft)
            else MaterialTheme.typography.labelLarge.copy(fontSize = 17.sp),
        )
    }
}

/**
 * Medidor horizontal de nivel (−90 a 0 dBFS) con la línea base (ámbar) y el umbral (rojo).
 * Se usa en Diagnóstico; Inicio usa [LevelBars].
 */
@Composable
fun LevelMeter(levelDb: Float, baselineDb: Float?, thresholdDb: Float?, modifier: Modifier = Modifier) {
    val min = -90f
    val max = 0f
    fun frac(db: Float) = ((db - min) / (max - min)).coerceIn(0f, 1f)
    Canvas(modifier.fillMaxWidth().height(28.dp)) {
        val r = CornerRadius(8.dp.toPx())
        drawRoundRect(Track, cornerRadius = r)
        val above = thresholdDb != null && levelDb > thresholdDb
        drawRoundRect(
            color = if (above) CoughRed.copy(alpha = 0.85f) else Primary,
            size = Size(size.width * frac(levelDb), size.height),
            cornerRadius = r,
        )
        baselineDb?.let {
            val x = size.width * frac(it)
            drawLine(SnoreAmber, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3.dp.toPx())
        }
        thresholdDb?.let {
            val x = size.width * frac(it)
            drawLine(CoughRed, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3.dp.toPx())
        }
    }
}

/**
 * Medidor de barras en vivo: cada barra es un instante reciente (la más nueva a la derecha).
 * Las que superan el umbral se pintan con el acento; la línea punteada marca el umbral.
 */
@Composable
fun LevelBars(levels: List<Float>, baselineDb: Float?, thresholdDb: Float?, modifier: Modifier = Modifier, bars: Int = 40) {
    val low = (baselineDb ?: -70f) - 8f
    val high = maxOf((thresholdDb ?: (low + 30f)) + 18f, low + 20f)
    fun frac(db: Float) = ((db - low) / (high - low)).coerceIn(0.06f, 1f)
    Canvas(modifier.fillMaxWidth().height(56.dp)) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (bars - 1)) / bars
        val r = CornerRadius(2.dp.toPx())
        val shown = levels.takeLast(bars)
        val offset = bars - shown.size
        for (i in 0 until bars) {
            val db = shown.getOrNull(i - offset)
            val h = size.height * (db?.let(::frac) ?: 0.06f)
            val lit = db != null && thresholdDb != null && db > thresholdDb
            drawRoundRect(
                color = if (lit) Primary else Track,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = r,
            )
        }
        thresholdDb?.let {
            val y = size.height * (1f - frac(it))
            var x = 0f
            val dash = 4.dp.toPx()
            while (x < size.width) {
                drawLine(OnSurfaceMuted.copy(alpha = 0.5f), Offset(x, y), Offset((x + dash).coerceAtMost(size.width), y), strokeWidth = 1.dp.toPx())
                x += dash * 2
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = stringResource(R.string.common_cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(title) },
        text = { Text(text, color = OnSurfaceSoft) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = Primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel, color = OnSurfaceMuted) } },
    )
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodySmall) {
    Text(text, modifier = modifier, style = style, color = OnSurfaceMuted)
}

/** Encabezado de pantalla: logo de barras + título, y algo opcional a la derecha. */
@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(BarsIcon, null, tint = Primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/**
 * Resumen de una noche en casillas de 3 por fila: duración y cada categoría que tuvo algo
 * (el ronquido como tiempo total, el resto como veces).
 */
@Composable
fun NightStatTiles(durationMs: Long, snoreMs: Long, speech: Int, cough: Int, other: Int, modifier: Modifier = Modifier) {
    data class T(val label: String, val value: String, val color: Color)
    val durationLabel = stringResource(R.string.common_tile_duration)
    val snoreLabel = stringResource(R.string.common_tile_snore)
    val speechLabel = stringResource(R.string.common_tile_speech)
    val coughLabel = stringResource(R.string.common_tile_cough)
    val otherLabel = stringResource(R.string.common_tile_other)
    val eventsLabel = stringResource(R.string.common_tile_events)
    val tiles = buildList {
        add(T(durationLabel, formatDurationShort(durationMs), OnSurface))
        if (snoreMs > 0) add(T(snoreLabel, formatDurationShort(snoreMs), SnoreAmber))
        if (speech > 0) add(T(speechLabel, "$speech×", Primary))
        if (cough > 0) add(T(coughLabel, "$cough×", CoughRed))
        if (other > 0) add(T(otherLabel, "$other×", OnSurface))
        if (size == 1) add(T(eventsLabel, "0", OnSurfaceMuted))
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(3).forEach { row ->
            TileRow {
                row.forEach { StatTile(it.value, it.label, Modifier.weight(1f), valueColor = it.color, labelFirst = true) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Fila de casillas del mismo ancho. */
@Composable
fun TileRow(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
