package com.nochesonora.transcription

import com.nochesonora.core.audio.WavWriter
import com.nochesonora.transcription.TranscriptionSupport.LanguageChoice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class TranscriptionSupportTest {
    @Test
    fun candidatesPreferDeviceCountryWhenSameLanguage() {
        assertEquals(
            listOf("es-CO", "es-ES", "es-US", "es"),
            TranscriptionSupport.candidateTags("es", Locale.forLanguageTag("es-CO")),
        )
        // Teléfono en inglés, app en español: no se usa el país del teléfono.
        assertEquals(listOf("es-ES", "es-US", "es"), TranscriptionSupport.candidateTags("es", Locale.forLanguageTag("en-US")))
        assertEquals(listOf("en-US", "en-GB", "en"), TranscriptionSupport.candidateTags("en", Locale.forLanguageTag("en-US")))
    }

    @Test
    fun chooseInstalledThenDownloadable() {
        val c = listOf("es-CO", "es-ES", "es-US", "es")
        assertEquals(
            LanguageChoice.Installed("es-US"),
            TranscriptionSupport.chooseLanguage(c, installed = listOf("en-US", "es-US"), pending = emptyList(), supported = listOf("es-ES")),
        )
        // Otra variante instalada del mismo idioma gana a descargar la preferida.
        assertEquals(
            LanguageChoice.Installed("es-MX"),
            TranscriptionSupport.chooseLanguage(c, installed = listOf("es-MX"), pending = emptyList(), supported = listOf("es-ES")),
        )
        assertEquals(
            LanguageChoice.Downloadable("es-ES"),
            TranscriptionSupport.chooseLanguage(c, installed = listOf("en-US"), pending = emptyList(), supported = listOf("fr-FR", "es-ES")),
        )
        // Mayúsculas y "_" no importan.
        assertEquals(
            LanguageChoice.Installed("es_es"),
            TranscriptionSupport.chooseLanguage(c, installed = listOf("es_es"), pending = emptyList(), supported = emptyList()),
        )
    }

    @Test
    fun unsupportedOrUnknown() {
        val c = listOf("es-ES", "es")
        assertEquals(
            LanguageChoice.Unsupported,
            TranscriptionSupport.chooseLanguage(c, installed = listOf("en-US"), pending = emptyList(), supported = listOf("fr-FR")),
        )
        assertEquals(LanguageChoice.Unknown("es-ES"), TranscriptionSupport.chooseLanguage(c, emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun pcmFromMurmurWav() {
        val samples = ShortArray(100) { (it * 300 - 15_000).toShort() }
        val wav = ByteArrayOutputStream().also { WavWriter.write(it, samples) }.toByteArray()
        val pcm = TranscriptionSupport.pcmFromWav(wav)!!
        assertEquals(200, pcm.size)
        val expected = ByteBuffer.allocate(200).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()
        assertArrayEquals(expected, pcm)
    }

    @Test
    fun pcmFromWavWithExtraChunkAndOddData() {
        val out = ByteArrayOutputStream()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun le16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        ascii("RIFF"); le32(0); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(1); le32(16_000); le32(32_000); le16(2); le16(16)
        ascii("LIST"); le32(3); out.write(byteArrayOf(1, 2, 3, 0)) // bloque impar + relleno
        ascii("data"); le32(5); out.write(byteArrayOf(10, 11, 12, 13, 14))
        assertArrayEquals(byteArrayOf(10, 11, 12, 13), TranscriptionSupport.pcmFromWav(out.toByteArray()))
    }

    @Test
    fun rejectsNonPcmOrStereoOrGarbage() {
        assertNull(TranscriptionSupport.pcmFromWav(ByteArray(10)))
        assertNull(TranscriptionSupport.pcmFromWav("RIFF....AVI LIST".toByteArray()))
        val out = ByteArrayOutputStream()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun le16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        ascii("RIFF"); le32(0); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(2); le32(16_000); le32(64_000); le16(4); le16(16)
        ascii("data"); le32(4); out.write(ByteArray(4))
        assertNull(TranscriptionSupport.pcmFromWav(out.toByteArray()))
    }

    @Test
    fun segmentsAreJoinedTrimmedAndCapitalized() {
        val es = Locale.forLanguageTag("es")
        assertEquals("No me dejes ahí ya voy", TranscriptionSupport.joinSegments(listOf(" no me dejes ahí", null, "", "ya voy "), es))
        assertEquals("", TranscriptionSupport.joinSegments(listOf(null, " "), es))
    }

    @Test
    fun timeoutIsClipLengthPlusMargin() {
        assertEquals(28_000L, TranscriptionSupport.timeoutMs(pcmBytes = 16_000 * 2 * 8))
    }
}
