package com.nochesonora.transcription

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Lógica pura de la transcripción (sin android.*), probada en la JVM. */
object TranscriptionSupport {
    /** Idioma por defecto del reconocedor para cada idioma de la app. */
    private val defaultTag = mapOf("es" to "es-ES", "en" to "en-US")

    /**
     * Idiomas a intentar, en orden: idioma de la app con el país del teléfono si el teléfono está
     * en ese idioma (es-CO), luego la variante por defecto (es-ES / en-US), luego es-US / en-GB.
     */
    fun candidateTags(appLanguage: String, deviceLocale: Locale): List<String> = buildList {
        if (deviceLocale.language == appLanguage && deviceLocale.country.isNotEmpty()) add("$appLanguage-${deviceLocale.country}")
        defaultTag[appLanguage]?.let(::add)
        when (appLanguage) {
            "es" -> add("es-US")
            "en" -> add("en-GB")
        }
        add(appLanguage)
    }.distinct()

    sealed interface LanguageChoice {
        val tag: String
        /** El modelo de ese idioma ya está en el teléfono. */
        data class Installed(override val tag: String) : LanguageChoice
        /** Se puede descargar (lo descarga el sistema, no la app). */
        data class Downloadable(override val tag: String) : LanguageChoice
        /** No hay datos de soporte (p. ej. el servicio no los da): se intenta igual. */
        data class Unknown(override val tag: String) : LanguageChoice
        /** El reconocedor del teléfono no tiene este idioma. */
        data object Unsupported : LanguageChoice {
            override val tag: String = ""
        }
    }

    /**
     * Elige el idioma con lo que informa `checkRecognitionSupport`. Prioridad: un candidato
     * instalado; otra variante instalada del mismo idioma; un candidato descargable; otra variante
     * descargable. Las etiquetas se comparan sin distinguir `_`/`-` ni mayúsculas.
     */
    fun chooseLanguage(
        candidates: List<String>,
        installed: List<String>,
        pending: List<String>,
        supported: List<String>,
    ): LanguageChoice {
        val language = candidates.firstOrNull()?.substringBefore('-') ?: return LanguageChoice.Unsupported
        fun norm(tag: String) = tag.replace('_', '-').lowercase(Locale.ROOT)
        fun pick(pool: List<String>): String? {
            val byNorm = pool.associateBy(::norm)
            candidates.firstNotNullOfOrNull { byNorm[norm(it)] }?.let { return it }
            return pool.firstOrNull { norm(it).substringBefore('-') == language }
        }
        pick(installed)?.let { return LanguageChoice.Installed(it) }
        // Una descarga pendiente sigue siendo "descargable": el sistema la termina solo.
        pick(pending + supported)?.let { return LanguageChoice.Downloadable(it) }
        if (installed.isEmpty() && pending.isEmpty() && supported.isEmpty()) return LanguageChoice.Unknown(candidates.first())
        return LanguageChoice.Unsupported
    }

    /**
     * El PCM16 de un WAV: busca el bloque `data` (no asume 44 bytes, aunque los clips de Murmur
     * siempre los tienen). Devuelve null si no es un WAV PCM16 mono.
     */
    fun pcmFromWav(bytes: ByteArray): ByteArray? {
        if (bytes.size < 12) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var pos = 12
        var pcm16Mono = false
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4).toLong() and 0xFFFF_FFFFL
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    if (body + 16 > bytes.size) return null
                    val format = bb.getShort(body).toInt()
                    val channels = bb.getShort(body + 2).toInt()
                    val bits = bb.getShort(body + 14).toInt()
                    pcm16Mono = format == 1 && channels == 1 && bits == 16
                }
                "data" -> {
                    if (!pcm16Mono) return null
                    val end = minOf(bytes.size.toLong(), body + size).toInt()
                    // Un número impar de bytes dejaría media muestra: se recorta.
                    val len = (end - body) and 1.inv()
                    return bytes.copyOfRange(body, body + len)
                }
            }
            pos = (body + size + (size and 1)).toInt().takeIf { it > pos } ?: return null
        }
        return null
    }

    /**
     * Texto final de una o varias partes reconocidas (una por segmento). Quita espacios, une con
     * un espacio y pone mayúscula inicial. "" si no se entendió nada.
     */
    fun joinSegments(segments: List<String?>, locale: Locale): String =
        segments.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .joinToString(" ")
            .replaceFirstChar { it.titlecase(locale) }

    /** Tiempo máximo para transcribir un clip: su duración más un margen. */
    fun timeoutMs(pcmBytes: Int, sampleRate: Int = 16_000): Long =
        pcmBytes / 2L * 1000 / sampleRate + 20_000
}
