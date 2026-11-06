package com.nochesonora.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.nochesonora.core.audio.Adts
import com.nochesonora.core.audio.Pcm
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

/**
 * Full-night recording: AAC-LC 16 kHz mono 32 kbps in an ADTS stream (A11).
 * It is fed the same blocks as the capture. If anything fails, it disables itself and the
 * session carries on: it must never bring down the capture.
 */
class AacFullRecorder private constructor(
    private val codec: MediaCodec,
    private val out: FileOutputStream,
) : Closeable {
    private val buffered = BufferedOutputStream(out, 64 * 1024)
    private val info = MediaCodec.BufferInfo()
    private var samplesQueued = 0L
    private var samplesSinceFlush = 0L
    private var failed = false
    private var closed = false
    private var writeBuf = ByteArray(4096)

    /** Audio samples already encoded: tells us the file's duration. */
    val encodedMs: Long get() = Pcm.samplesToMs(samplesQueued)

    fun feed(samples: ShortArray, length: Int = samples.size) {
        if (failed || closed) return
        try {
            var offset = 0
            var attempts = 0
            while (offset < length) {
                val idx = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (idx < 0) {
                    drain(endOfStream = false)
                    if (++attempts > MAX_INPUT_ATTEMPTS) {
                        Log.w(TAG, "Encoder saturated: dropping a block")
                        return
                    }
                    continue
                }
                val buf = codec.getInputBuffer(idx) ?: continue
                buf.clear()
                val n = minOf(length - offset, buf.remaining() / 2)
                buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, offset, n)
                val ptsUs = samplesQueued * 1_000_000L / Pcm.SAMPLE_RATE
                codec.queueInputBuffer(idx, 0, n * 2, ptsUs, 0)
                samplesQueued += n
                samplesSinceFlush += n
                offset += n
            }
            drain(endOfStream = false)
            if (samplesSinceFlush >= FLUSH_EVERY_SAMPLES) {
                buffered.flush()
                samplesSinceFlush = 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Full recording failed; disabling it", e)
            failed = true
        }
    }

    private fun drain(endOfStream: Boolean) {
        var idle = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) OUTPUT_TIMEOUT_US else 0)
            when {
                idx >= 0 -> {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) {
                        val buf = codec.getOutputBuffer(idx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        if (writeBuf.size < info.size) writeBuf = ByteArray(info.size)
                        buf.get(writeBuf, 0, info.size)
                        buffered.write(Adts.header(info.size))
                        buffered.write(writeBuf, 0, info.size)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idle > 50) return
                }
                else -> Unit // INFO_OUTPUT_FORMAT_CHANGED: ADTS does not need it
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (!failed) {
                val idx = codec.dequeueInputBuffer(OUTPUT_TIMEOUT_US)
                if (idx >= 0) {
                    val ptsUs = samplesQueued * 1_000_000L / Pcm.SAMPLE_RATE
                    codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drain(endOfStream = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Incomplete AAC close", e)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { buffered.flush(); out.fd.sync() }
            runCatching { buffered.close() }
        }
    }

    companion object {
        /** Opens the encoder and the file. Throws if the device cannot encode AAC. */
        fun open(file: File): AacFullRecorder {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Pcm.SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                file.parentFile?.mkdirs()
                return AacFullRecorder(codec, FileOutputStream(file))
            } catch (e: Exception) {
                runCatching { codec.release() }
                throw e
            }
        }

        private const val TAG = "AacFullRecorder"
        const val BIT_RATE = 32_000
        private const val INPUT_TIMEOUT_US = 5_000L
        private const val OUTPUT_TIMEOUT_US = 10_000L
        private const val MAX_INPUT_ATTEMPTS = 20
        private const val FLUSH_EVERY_SAMPLES = 10L * Pcm.SAMPLE_RATE // 10 s
    }
}
