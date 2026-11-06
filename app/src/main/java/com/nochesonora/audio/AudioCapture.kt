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
 * `AudioRecord` a 16 kHz, mono, PCM16. Se lee en bloques de 100 ms desde un único hilo.
 *
 * Fuente (A15): `UNPROCESSED` si el equipo lo soporta; si no, `VOICE_RECOGNITION`, que por CDD
 * no lleva control automático de ganancia. `MIC` falsearía la línea base.
 */
class AudioCapture(private val context: Context) : AudioSource {
    private var record: AudioRecord? = null

    override var sourceName: String = ""
        private set

    override val audioSessionId: Int get() = record?.audioSessionId ?: AudioManager.AUDIO_SESSION_ID_GENERATE

    override val isOpen: Boolean get() = record != null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Crea y arranca el `AudioRecord`. Devuelve false si el micrófono no está disponible. */
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
            Log.w(TAG, "No se pudo abrir el micrófono", e)
            false
        }
    }

    /**
     * Lee exactamente `buffer.size` muestras (bloqueante). Devuelve las muestras leídas o un
     * código de error negativo de `AudioRecord` (`ERROR_DEAD_OBJECT`, `ERROR_INVALID_OPERATION`...).
     */
    override fun readBlock(buffer: ShortArray): Int {
        val r = record ?: return AudioRecord.ERROR_INVALID_OPERATION
        var offset = 0
        while (offset < buffer.size) {
            val n = r.read(buffer, offset, buffer.size - offset, AudioRecord.READ_BLOCKING)
            if (n < 0) return n
            if (n == 0) return AudioRecord.ERROR // no debería pasar en modo bloqueante; se trata como error
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
