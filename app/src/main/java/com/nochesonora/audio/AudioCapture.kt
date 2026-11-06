package com.nochesonora.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.nochesonora.core.audio.Pcm

/**
 * `AudioRecord` at 16 kHz, mono, PCM16. Read in 100 ms blocks from a single thread.
 *
 * Source (A15): `UNPROCESSED` if the device supports it; otherwise `VOICE_RECOGNITION`, which per
 * the CDD has no automatic gain control. `MIC` would distort the baseline.
 */
class AudioCapture(private val context: Context) : AudioSource {
    private var record: AudioRecord? = null

    override var sourceName: String = ""
        private set

    override val audioSessionId: Int get() = record?.audioSessionId ?: AudioManager.AUDIO_SESSION_ID_GENERATE

    override val isOpen: Boolean get() = record != null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Creates and starts the `AudioRecord`. Returns false if the microphone is unavailable. */
    @SuppressLint("MissingPermission")
    override fun open(): Boolean {
        release()
        if (!hasPermission()) return false
        val minSize = AudioRecord.getMinBufferSize(Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minSize <= 0) return false
        val bufferBytes = maxOf(minSize * 4, BLOCK_SAMPLES * 2 * 4)
        val source = chooseSource()
        return try {
            val r = AudioRecord(source, Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                return false
            }
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                r.release()
                return false
            }
            record = r
            sourceName = if (source == MediaRecorder.AudioSource.UNPROCESSED) "UNPROCESSED" else "VOICE_RECOGNITION"
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not open the microphone", e)
            false
        }
    }

    /**
     * Reads exactly `buffer.size` samples (blocking). Returns the number of samples read or a
     * negative `AudioRecord` error code (`ERROR_DEAD_OBJECT`, `ERROR_INVALID_OPERATION`...).
     */
    override fun readBlock(buffer: ShortArray): Int {
        val r = record ?: return AudioRecord.ERROR_INVALID_OPERATION
        var offset = 0
        while (offset < buffer.size) {
            val n = r.read(buffer, offset, buffer.size - offset, AudioRecord.READ_BLOCKING)
            if (n < 0) return n
            if (n == 0) return AudioRecord.ERROR // should not happen in blocking mode; treated as an error
            offset += n
        }
        return offset
    }

    override fun release() {
        val r = record ?: return
        record = null
        runCatching { r.stop() }
        runCatching { r.release() }
    }

    private fun chooseSource(): Int {
        val am = context.getSystemService(AudioManager::class.java)
        val unprocessed = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        return if (unprocessed == "true") MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    companion object {
        const val BLOCK_SAMPLES = 1_600 // 100 ms
        private const val TAG = "AudioCapture"
    }
}
