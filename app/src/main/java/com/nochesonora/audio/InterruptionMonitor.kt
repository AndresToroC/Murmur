package com.nochesonora.audio

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.nochesonora.data.db.PauseReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Detecta cuándo perdemos el micrófono sin error explícito (§6.5):
 * - API 29+: `isClientSilenced` de nuestra grabación (otra app tomó el micrófono y recibimos ceros).
 * - Llamadas: sondeo de `AudioManager.mode` cada 2 s (no requiere `READ_PHONE_STATE`).
 * Los errores de `read()` los trata el motor directamente.
 */
class InterruptionMonitor(
    context: Context,
    private val audioSessionId: () -> Int,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    @Volatile private var silenced = false
    @Volatile private var inCall = false

    private var pollJob: Job? = null
    private var callback: AudioManager.AudioRecordingCallback? = null

    /** Motivo de pausa actual ([PauseReason]) o null si se puede grabar. */
    val currentReason: String?
        get() = when {
            inCall -> PauseReason.LLAMADA
            silenced -> PauseReason.MICROFONO_OCUPADO
            else -> null
        }

    fun start(scope: CoroutineScope) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) registerSilenceCallback()
        pollJob = scope.launch {
            while (isActive) {
                val mode = runCatching { audioManager?.mode }.getOrNull()
                inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        callback?.let { runCatching { audioManager?.unregisterAudioRecordingCallback(it) } }
        callback = null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun registerSilenceCallback() {
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                update(configs)
            }
        }
        callback = cb
        audioManager?.registerAudioRecordingCallback(cb, Handler(Looper.getMainLooper()))
        audioManager?.activeRecordingConfigurations?.let { update(it) }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun update(configs: List<AudioRecordingConfiguration>) {
        val ours = configs.firstOrNull { it.clientAudioSessionId == audioSessionId() } ?: return
        silenced = ours.isClientSilenced
    }

    private companion object {
        const val POLL_MS = 2_000L
    }
}
