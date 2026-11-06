package com.nochesonora.audio

/** Source of 16 kHz PCM16 blocks. On the phone it is [AudioCapture]; in tests, recorded audio. */
interface AudioSource {
    val isOpen: Boolean
    val sourceName: String
    val audioSessionId: Int

    /** Opens the source. Returns false if it is unavailable. */
    fun open(): Boolean

    /** Reads exactly `buffer.size` samples or returns a negative error code. */
    fun readBlock(buffer: ShortArray): Int

    fun release()
}
