package com.nochesonora.transcription

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Pure transcription logic (no android.*), tested on the JVM. */
object TranscriptionSupport {
    /** Default recognizer language for each app language. */
    private val defaultTag = mapOf("es" to "es-ES", "en" to "en-US")

    /**
     * Languages to try, in order: the app language with the phone's country if the phone is set
     * to that language (es-CO), then the default variant (es-ES / en-US), then es-US / en-GB.
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
        /** The model for that language is already on the phone. */
        data class Installed(override val tag: String) : LanguageChoice
        /** Can be downloaded (the system downloads it, not the app). */
        data class Downloadable(override val tag: String) : LanguageChoice
        /** No support data (e.g. the service does not provide it): try anyway. */
        data class Unknown(override val tag: String) : LanguageChoice
        /** The phone's recognizer does not have this language. */
        data object Unsupported : LanguageChoice {
            override val tag: String = ""
        }
    }

    /**
     * Picks the language from what `checkRecognitionSupport` reports. Priority: an installed
     * candidate; another installed variant of the same language; a downloadable candidate; another
     * downloadable variant. Tags are compared ignoring `_`/`-` and case.
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
        // A pending download still counts as "downloadable": the system finishes it on its own.
        pick(pending + supported)?.let { return LanguageChoice.Downloadable(it) }
        if (installed.isEmpty() && pending.isEmpty() && supported.isEmpty()) return LanguageChoice.Unknown(candidates.first())
        return LanguageChoice.Unsupported
    }

    /**
     * The PCM16 of a WAV: looks for the `data` chunk (does not assume 44 bytes, although Murmur's
     * clips always have them). Returns null if it is not a mono PCM16 WAV.
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
                    // An odd number of bytes would leave half a sample: trim it.
                    val len = (end - body) and 1.inv()
                    return bytes.copyOfRange(body, body + len)
                }
            }
            pos = (body + size + (size and 1)).toInt().takeIf { it > pos } ?: return null
        }
        return null
    }

    /**
     * Final text from one or more recognized parts (one per segment). Trims spaces, joins with a
     * single space and capitalizes the first letter. "" if nothing was understood.
     */
    fun joinSegments(segments: List<String?>, locale: Locale): String =
        segments.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .joinToString(" ")
            .replaceFirstChar { it.titlecase(locale) }

    /** Maximum time to transcribe a clip: its duration plus a margin. */
    fun timeoutMs(pcmBytes: Int, sampleRate: Int = 16_000): Long =
        pcmBytes / 2L * 1000 / sampleRate + 20_000
}
