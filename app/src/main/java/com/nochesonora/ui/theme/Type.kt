package com.nochesonora.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.nochesonora.R

// Fuentes incluidas en el APK (res/font, licencia OFL): la app no usa red.
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Inter Tight: textos. */
val SansFamily = FontFamily(
    variable(R.font.intertight, 400),
    variable(R.font.intertight, 500),
    variable(R.font.intertight, 600),
    variable(R.font.intertight, 700),
)

/** JetBrains Mono: horas, niveles en dB, contadores. */
val MonoFamily = FontFamily(
    variable(R.font.jetbrainsmono, 400),
    variable(R.font.jetbrainsmono, 500),
)

private fun sans(size: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = SansFamily, fontSize = size.sp, fontWeight = weight)

val AppTypography = Typography(
    headlineMedium = sans(26, FontWeight.Bold).copy(letterSpacing = (-0.01).em),
    titleLarge = sans(20, FontWeight.SemiBold),
    titleMedium = sans(16, FontWeight.SemiBold),
    titleSmall = sans(14, FontWeight.SemiBold),
    bodyLarge = sans(16),
    bodyMedium = sans(14),
    bodySmall = sans(12),
    labelLarge = sans(15, FontWeight.SemiBold),
    labelMedium = sans(12, FontWeight.Medium),
    labelSmall = sans(11, FontWeight.Medium),
)

/** Estilos propios del diseño. */
object Murmur {
    /** Reloj grande de la sesión. */
    val clock = TextStyle(fontFamily = MonoFamily, fontSize = 56.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.02).em, lineHeight = 56.sp)
    /** Cifras de las casillas (contadores, duración). */
    val stat = TextStyle(fontFamily = MonoFamily, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    val monoBody = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp)
    val monoSmall = TextStyle(fontFamily = MonoFamily, fontSize = 12.sp)
    val monoTiny = TextStyle(fontFamily = MonoFamily, fontSize = 11.sp)
    /** Títulos de sección en mayúsculas ("LO ÚLTIMO QUE SE ESCUCHÓ"). */
    val section = TextStyle(fontFamily = SansFamily, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.08.em)
}
