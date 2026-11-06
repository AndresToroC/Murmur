package com.nochesonora.audio

/** Fuente de bloques PCM16 a 16 kHz. En el teléfono es [AudioCapture]; en los tests, un audio grabado. */
interface AudioSource {
    val isOpen: Boolean
    val sourceName: String
    val audioSessionId: Int

    /** Abre la fuente. Devuelve false si no está disponible. */
    fun open(): Boolean

    /** Lee exactamente `buffer.size` muestras o devuelve un código de error negativo. */
    fun readBlock(buffer: ShortArray): Int

    fun release()
}
