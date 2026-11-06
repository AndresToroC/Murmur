package com.nochesonora.ui.theme

import androidx.compose.ui.graphics.Color
import com.nochesonora.core.classification.Category

// Diseño "Murmur · Instrumento": azul noche, superficies con borde fino y números monoespaciados.
val Background = Color(0xFF0A0F1C)
val Surface = Color(0xFF111829)
val SurfaceHigh = Color(0xFF151D31)
/** Borde de tarjetas y casillas. */
val Border = Color(0xFF1E2940)
/** Borde de botones y de la tarjeta abierta. */
val BorderStrong = Color(0xFF2E3B58)
/** Barras apagadas del medidor y parte no reproducida de la onda. */
val Track = Color(0xFF2A3756)
val OnSurface = Color(0xFFE7ECF5)
val OnSurfaceSoft = Color(0xFFC9D3E3)
val OnSurfaceMuted = Color(0xFF93A0B8)
val Primary = Color(0xFF7CC4FF)
val OnPrimary = Color(0xFF0A0F1C)
val PauseGray = Color(0xFF3A4660)

// Colores por categoría (contraste AA sobre el fondo).
val SnoreAmber = Color(0xFFF2B44B)
val SpeechBlue = Color(0xFF7CC4FF)
val CoughRed = Color(0xFFFF8A80)
val OtherGray = Color(0xFF9AA6B8)

fun Category.color(): Color = when (this) {
    Category.RONQUIDO -> SnoreAmber
    Category.HABLA -> SpeechBlue
    Category.TOS -> CoughRed
    Category.OTRO, Category.FONDO -> OtherGray
}
