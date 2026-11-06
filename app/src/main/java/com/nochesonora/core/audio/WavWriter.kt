package com.nochesonora.core.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** Writes mono PCM16 as WAV (44-byte RIFF header). */
object WavWriter {
    const val HEADER_SIZE = 44

    /**
     * Atomic write to disk: write to `.tmp`, sync, then rename.
     *
     * The order matters: flush → sync → close. Syncing after close always throws
     * `SyncFailedException`, which meant no clip was ever saved (plan §12, B4).
     */
    @Throws(IOException::class)
    fun writeFileAtomic(target: File, samples: ShortArray, sampleRate: Int = Pcm.SAMPLE_RATE) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            val out = BufferedOutputStream(fos, 64 * 1024)
            write(out, samples, sampleRate)
            out.flush()
            fos.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Could not rename ${tmp.name}")
        }
    }

    fun write(out: OutputStream, samples: ShortArray, sampleRate: Int = Pcm.SAMPLE_RATE) {
        out.write(header(samples.size, sampleRate))
        val buf = ByteArray(8192)
        var i = 0
        while (i < samples.size) {
            val n = minOf((buf.size / 2), samples.size - i)
            for (k in 0 until n) {
                val s = samples[i + k].toInt()
                buf[2 * k] = (s and 0xFF).toByte()
                buf[2 * k + 1] = ((s shr 8) and 0xFF).toByte()
            }
            out.write(buf, 0, n * 2)
            i += n
        }
    }

    fun header(numSamples: Int, sampleRate: Int = Pcm.SAMPLE_RATE): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val dataSize = numSamples * channels * bitsPerSample / 8
        val h = ByteArray(HEADER_SIZE)
        fun ascii(pos: Int, s: String) = s.forEachIndexed { i, c -> h[pos + i] = c.code.toByte() }
        fun int32(pos: Int, v: Int) {
            h[pos] = (v and 0xFF).toByte()
            h[pos + 1] = ((v shr 8) and 0xFF).toByte()
            h[pos + 2] = ((v shr 16) and 0xFF).toByte()
            h[pos + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun int16(pos: Int, v: Int) {
            h[pos] = (v and 0xFF).toByte()
            h[pos + 1] = ((v shr 8) and 0xFF).toByte()
        }
        ascii(0, "RIFF")
        int32(4, 36 + dataSize)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        int32(16, 16)
        int16(20, 1) // PCM
        int16(22, channels)
        int32(24, sampleRate)
        int32(28, byteRate)
        int16(32, channels * bitsPerSample / 8)
        int16(34, bitsPerSample)
        ascii(36, "data")
        int32(40, dataSize)
        return h
    }
}
