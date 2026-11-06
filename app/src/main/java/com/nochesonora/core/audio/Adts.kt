package com.nochesonora.core.audio

/**
 * Cabecera ADTS de 7 bytes (sin CRC) para AAC-LC. Con ella, `full.aac` es un flujo
 * reproducible aunque la app muera a mitad de la noche (decisión A11).
 */
object Adts {
    const val HEADER_SIZE = 7
    private val SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
    )

    fun header(payloadSize: Int, sampleRate: Int = Pcm.SAMPLE_RATE, channels: Int = 1): ByteArray {
        val freqIdx = SAMPLE_RATES.indexOf(sampleRate)
        require(freqIdx >= 0) { "Frecuencia no soportada por ADTS: $sampleRate" }
        val profile = 2 // AAC LC (audio object type 2)
        val len = payloadSize + HEADER_SIZE
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(), // MPEG-4, sin CRC
            (((profile - 1) shl 6) or (freqIdx shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (len shr 11)).toByte(),
            ((len and 0x7FF) shr 3).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }
}
