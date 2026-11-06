package com.nochesonora.transcription

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import com.nochesonora.data.settings.AppLanguage
import com.nochesonora.transcription.TranscriptionSupport.LanguageChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** Si el teléfono puede transcribir, y en qué idioma. */
sealed interface TranscriberAvailability {
    /** Android < 13 o sin reconocimiento de voz en el teléfono (sin conexión). */
    data object NotSupported : TranscriberAvailability
    /** El reconocedor del teléfono no tiene el idioma de la app. */
    data object LanguageMissing : TranscriberAvailability
    /** El idioma se puede descargar (lo descarga el sistema). */
    data class NeedsDownload(val languageTag: String) : TranscriberAvailability
    data class Ready(val languageTag: String) : TranscriberAvailability
}

sealed interface TranscriptResult {
    /** "" = se escuchó pero no se entendió ninguna palabra. */
    data class Text(val text: String) : TranscriptResult
    /** El modelo del idioma no está en el teléfono. */
    data object LanguageUnavailable : TranscriptResult
    data class Failed(val error: Int) : TranscriptResult
}

/**
 * Transcribe clips WAV con el reconocedor de voz **del teléfono, sin conexión**
 * (`createOnDeviceSpeechRecognizer`). Nunca usa el reconocedor en red y la app sigue sin el permiso
 * INTERNET. El audio se le pasa desde el archivo (API 33+, `EXTRA_AUDIO_SOURCE`), no del micrófono.
 *
 * Antes, [SpeechPrep] recorta los silencios, sube el volumen y parte el clip en frases; cada frase
 * se reconoce aparte (así el reconocedor no se rinde con un murmullo ni corta en la primera pausa).
 *
 * SpeechRecognizer exige el hilo principal: todas sus llamadas van en `Dispatchers.Main`; la lectura
 * del WAV y la escritura al pipe, en IO. Una sola transcripción a la vez en todo el proceso.
 */
class SpeechTranscriber(private val context: Context) {

    private val candidates: List<String>
        get() {
            // Idioma del sistema (no el de la app, que ya viene forzado por AppLanguage.wrap).
            val device = Resources.getSystem().configuration.locales[0]
            return TranscriptionSupport.candidateTags(AppLanguage.get(context).tag, device)
        }

    suspend fun availability(): TranscriberAvailability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return TranscriberAvailability.NotSupported
        val available = withContext(Dispatchers.Main) {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        }
        if (!available) return TranscriberAvailability.NotSupported
        val tags = candidates
        val support = querySupport(tags.first())
        val choice = if (support == null) {
            LanguageChoice.Unknown(tags.first())
        } else {
            TranscriptionSupport.chooseLanguage(tags, support.installed, support.pending, support.supported)
        }
        return when (choice) {
            is LanguageChoice.Installed, is LanguageChoice.Unknown -> TranscriberAvailability.Ready(choice.tag)
            is LanguageChoice.Downloadable -> TranscriberAvailability.NeedsDownload(choice.tag)
            LanguageChoice.Unsupported -> TranscriberAvailability.LanguageMissing
        }
    }

    /** Pide al sistema que descargue el idioma (la descarga la hace Android, no Murmur). */
    suspend fun downloadLanguage(languageTag: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        withContext(Dispatchers.Main) {
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            try {
                recognizer.triggerModelDownload(recognizeIntent(languageTag))
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo pedir la descarga del idioma $languageTag", e)
            } finally {
                recognizer.destroy()
            }
        }
    }

    suspend fun transcribe(wavPath: String, languageTag: String): TranscriptResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return TranscriptResult.Failed(SpeechRecognizer.ERROR_CLIENT)
        val phrases = withContext(Dispatchers.IO) {
            try {
                TranscriptionSupport.pcmFromWav(File(wavPath).readBytes())
                    ?.let { SpeechPrep.phrases(SpeechPrep.toSamples(it)) }
            } catch (e: IOException) {
                Log.w(TAG, "No se pudo leer $wavPath", e)
                null
            }
        } ?: return TranscriptResult.Failed(SpeechRecognizer.ERROR_CLIENT)
        return lock.withLock { recognizePhrases(phrases, languageTag) }
    }

    /**
     * Reconoce cada frase por separado y une lo que se entendió. Una frase que falla no tumba las
     * demás; si el idioma no está, se para ahí.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun recognizePhrases(phrases: List<ShortArray>, languageTag: String): TranscriptResult {
        val texts = mutableListOf<String?>()
        var lastError: Int? = null
        var anyHeard = false
        for ((i, phrase) in phrases.withIndex()) {
            if (i > 0) delay(BETWEEN_PHRASES_MS) // el servicio libera el reconocedor anterior
            val pcm = SpeechPrep.toBytes(phrase)
            var outcome = recognizeOnce(pcm, languageTag)
            if (outcome is Outcome.Error && outcome.error in RETRYABLE) {
                delay(RETRY_DELAY_MS)
                outcome = recognizeOnce(pcm, languageTag)
            }
            when (outcome) {
                is Outcome.Heard -> {
                    texts += outcome.text
                    anyHeard = true
                }
                Outcome.LanguageUnavailable -> return TranscriptResult.LanguageUnavailable
                is Outcome.Error -> {
                    Log.w(TAG, "Frase ${i + 1}/${phrases.size}: error ${outcome.error}")
                    lastError = outcome.error
                }
            }
        }
        val locale = Resources.getSystem().configuration.locales[0]
        val error = lastError
        return if (anyHeard || error == null) TranscriptResult.Text(TranscriptionSupport.joinSegments(texts, locale))
        else TranscriptResult.Failed(error)
    }

    /** Resultado de reconocer una frase. */
    private sealed interface Outcome {
        /** "" = sin palabras reconocibles. */
        data class Heard(val text: String) : Outcome
        data object LanguageUnavailable : Outcome
        data class Error(val error: Int) : Outcome
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun recognizeOnce(pcm: ByteArray, languageTag: String): Outcome {
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        // Escritor aparte (no hijo de esta corrutina): el pipe se bloquea hasta que el reconocedor
        // lee, y si el reconocedor deja de leer no debe colgar la transcripción. Escribe por
        // trozos para poder pararse; al cerrar, el reconocedor ve el fin del audio.
        val writer = writerScope.launch {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                    var offset = 0
                    while (offset < pcm.size && isActive) {
                        val n = minOf(WRITE_CHUNK, pcm.size - offset)
                        out.write(pcm, offset, n)
                        offset += n
                    }
                }
            } catch (e: IOException) {
                Log.d(TAG, "El reconocedor cerró el audio antes de terminar", e) // p. ej. al cancelar
            }
        }
        // Lo último que el reconocedor iba entendiendo: si al final no da resultado (o se acaba el
        // tiempo), es mejor que nada.
        val partial = AtomicReference<String?>(null)
        return try {
            withContext(Dispatchers.Main) {
                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                try {
                    withTimeoutOrNull(TranscriptionSupport.timeoutMs(pcm.size)) {
                        listen(recognizer, recognizeIntent(languageTag).withAudioSource(readSide), partial)
                    } ?: Outcome.Heard(partial.get().orEmpty())
                } finally {
                    recognizer.cancel()
                    recognizer.destroy()
                }
            }
        } finally {
            runCatching { readSide.close() }
            writer.cancel()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun listen(recognizer: SpeechRecognizer, intent: Intent, partial: AtomicReference<String?>): Outcome =
        suspendCancellableCoroutine { cont ->
            fun finish(result: Outcome) {
                if (cont.isActive) cont.resume(result)
            }
            fun Bundle?.best(): String? = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults.best()?.let(partial::set)
                }

                override fun onResults(results: Bundle) {
                    finish(Outcome.Heard(results.best() ?: partial.get().orEmpty()))
                }

                override fun onError(error: Int) {
                    finish(
                        when (error) {
                            // Sin palabras reconocibles: es un resultado, no un fallo.
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                Outcome.Heard(partial.get().orEmpty())
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                                Outcome.LanguageUnavailable
                            else -> partial.get()?.let { Outcome.Heard(it) } ?: Outcome.Error(error)
                        },
                    )
                }

                override fun onSegmentResults(segmentResults: Bundle) = Unit
                override fun onEndOfSegmentedSession() = Unit
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            recognizer.startListening(intent)
        }

    /** Idiomas instalados / pendientes / soportados por el reconocedor del teléfono, o null. */
    private data class Support(val installed: List<String>, val pending: List<String>, val supported: List<String>)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun querySupport(languageTag: String): Support? = withContext(Dispatchers.Main) {
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        try {
            withTimeoutOrNull(SUPPORT_TIMEOUT_MS) {
                suspendCancellableCoroutine<Support?> { cont ->
                    recognizer.checkRecognitionSupport(
                        recognizeIntent(languageTag),
                        context.mainExecutor,
                        object : RecognitionSupportCallback {
                            override fun onSupportResult(support: RecognitionSupport) {
                                if (cont.isActive) cont.resume(support.toSupport())
                            }

                            override fun onError(error: Int) {
                                Log.w(TAG, "checkRecognitionSupport falló: $error")
                                if (cont.isActive) cont.resume(null)
                            }
                        },
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo consultar el soporte de idiomas", e)
            null
        } finally {
            recognizer.destroy()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun RecognitionSupport.toSupport(): Support =
        Support(installedOnDeviceLanguages, pendingOnDeviceLanguages, supportedOnDeviceLanguages)

    private fun recognizeIntent(languageTag: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        // Los parciales sirven de respaldo cuando el resultado final llega vacío.
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun Intent.withAudioSource(source: ParcelFileDescriptor) = apply {
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
        // Sin sesión segmentada: SpeechPrep ya entrega cada frase recortada, y no todos los
        // reconocedores la admiten.
    }

    private companion object {
        const val TAG = "SpeechTranscriber"
        const val SAMPLE_RATE = 16_000
        const val SUPPORT_TIMEOUT_MS = 5_000L
        /** 0,5 s de audio por escritura. */
        const val WRITE_CHUNK = 16_000
        const val BETWEEN_PHRASES_MS = 150L
        const val RETRY_DELAY_MS = 600L
        /** Errores pasajeros del servicio: se reintenta la frase una vez (solo se usa en API 33+). */
        @SuppressLint("InlinedApi")
        val RETRYABLE = setOf(
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_AUDIO,
        )
        /** Escritores del audio al reconocedor; viven fuera de la corrutina que transcribe. */
        val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        /** Un único reconocedor activo en todo el proceso. */
        val lock = Mutex()
    }
}
