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
import com.nochesonora.data.settings.Language
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

/** Whether the phone can transcribe, and in which language. */
sealed interface TranscriberAvailability {
    /** Android < 13 or no on-device (offline) speech recognition. */
    data object NotSupported : TranscriberAvailability
    /** The phone's recognizer does not have the app's language. */
    data object LanguageMissing : TranscriberAvailability
    /** The language can be downloaded (the system downloads it). */
    data class NeedsDownload(val languageTag: String) : TranscriberAvailability
    /**
     * [confirmed] = the phone reported the language as installed. If false, the phone gave no
     * language data: Murmur tries anyway, but the recognizer may silently use another language.
     */
    data class Ready(val languageTag: String, val confirmed: Boolean = true) : TranscriberAvailability
}

sealed interface TranscriptResult {
    /** "" = something was heard but no word was understood. */
    data class Text(val text: String) : TranscriptResult
    /** The language model is not on the phone. */
    data object LanguageUnavailable : TranscriptResult
    data class Failed(val error: Int) : TranscriptResult
}

/**
 * Transcribes WAV clips with the **phone's offline** speech recognizer
 * (`createOnDeviceSpeechRecognizer`). It never uses the network recognizer, and the app still has
 * no INTERNET permission. Audio is fed from the file (API 33+, `EXTRA_AUDIO_SOURCE`), not the mic.
 *
 * First, [SpeechPrep] trims silences, raises the volume and splits the clip into phrases; each
 * phrase is recognized separately (so the recognizer neither gives up on a mumble nor cuts off at
 * the first pause).
 *
 * SpeechRecognizer requires the main thread: all its calls run on `Dispatchers.Main`; reading the
 * WAV and writing to the pipe run on IO. Only one transcription at a time in the whole process.
 */
class SpeechTranscriber(private val context: Context) {

    /** [language]: the one chosen in Settings, or null for the app's language. */
    private fun candidates(language: Language?): List<String> {
        // System locale (not the app's, which AppLanguage.wrap already forces), for the country.
        val device = Resources.getSystem().configuration.locales[0]
        return TranscriptionSupport.candidateTags((language ?: AppLanguage.get(context)).tag, device)
    }

    suspend fun availability(language: Language?): TranscriberAvailability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return TranscriberAvailability.NotSupported
        val available = withContext(Dispatchers.Main) {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        }
        if (!available) return TranscriberAvailability.NotSupported
        val tags = candidates(language)
        val support = querySupport(tags.first())
        val choice = if (support == null) {
            LanguageChoice.Unknown(tags.first())
        } else {
            TranscriptionSupport.chooseLanguage(tags, support.installed, support.pending, support.supported)
        }
        return when (choice) {
            is LanguageChoice.Installed -> TranscriberAvailability.Ready(choice.tag, confirmed = true)
            is LanguageChoice.Unknown -> TranscriberAvailability.Ready(choice.tag, confirmed = false)
            is LanguageChoice.Downloadable -> TranscriberAvailability.NeedsDownload(choice.tag)
            LanguageChoice.Unsupported -> TranscriberAvailability.LanguageMissing
        }
    }

    /** Asks the system to download the language (Android downloads it, not Murmur). Also used to
     *  make sure the model is there when the phone could not confirm it. */
    suspend fun downloadLanguage(languageTag: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        withContext(Dispatchers.Main) {
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            try {
                recognizer.triggerModelDownload(recognizeIntent(languageTag))
            } catch (e: Exception) {
                Log.w(TAG, "Could not request download of language $languageTag", e)
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
                Log.w(TAG, "Could not read $wavPath", e)
                null
            }
        } ?: return TranscriptResult.Failed(SpeechRecognizer.ERROR_CLIENT)
        return lock.withLock { recognizePhrases(phrases, languageTag) }
    }

    /**
     * Recognizes each phrase separately and joins what was understood. A failing phrase does not
     * sink the others; if the language is missing, it stops there.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun recognizePhrases(phrases: List<ShortArray>, languageTag: String): TranscriptResult {
        val texts = mutableListOf<String?>()
        var lastError: Int? = null
        var anyHeard = false
        for ((i, phrase) in phrases.withIndex()) {
            if (i > 0) delay(BETWEEN_PHRASES_MS) // the service releases the previous recognizer
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
                    Log.w(TAG, "Phrase ${i + 1}/${phrases.size}: error ${outcome.error}")
                    lastError = outcome.error
                }
            }
        }
        val locale = Resources.getSystem().configuration.locales[0]
        val error = lastError
        return if (anyHeard || error == null) TranscriptResult.Text(TranscriptionSupport.joinSegments(texts, locale))
        else TranscriptResult.Failed(error)
    }

    /** Result of recognizing one phrase. */
    private sealed interface Outcome {
        /** "" = no recognizable words. */
        data class Heard(val text: String) : Outcome
        data object LanguageUnavailable : Outcome
        data class Error(val error: Int) : Outcome
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun recognizeOnce(pcm: ByteArray, languageTag: String): Outcome {
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        // Separate writer (not a child of this coroutine): the pipe blocks until the recognizer
        // reads, and if the recognizer stops reading it must not hang the transcription. Writes in
        // chunks so it can stop; on close, the recognizer sees the end of the audio.
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
                Log.d(TAG, "The recognizer closed the audio before it finished", e) // e.g. when cancelling
            }
        }
        // The latest text the recognizer had understood: if it gives no final result (or time
        // runs out), it is better than nothing.
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
                            // No recognizable words: that is a result, not a failure.
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

    /** Languages installed / pending / supported by the phone's recognizer, or null. */
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
                                Log.w(TAG, "checkRecognitionSupport failed: $error")
                                if (cont.isActive) cont.resume(null)
                            }
                        },
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query language support", e)
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
        // Partial results serve as a fallback when the final result comes back empty.
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun Intent.withAudioSource(source: ParcelFileDescriptor) = apply {
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
        // No segmented session: SpeechPrep already delivers each phrase trimmed, and not every
        // recognizer supports it.
    }

    private companion object {
        const val TAG = "SpeechTranscriber"
        const val SAMPLE_RATE = 16_000
        const val SUPPORT_TIMEOUT_MS = 5_000L
        /** 0.5 s of audio per write. */
        const val WRITE_CHUNK = 16_000
        const val BETWEEN_PHRASES_MS = 150L
        const val RETRY_DELAY_MS = 600L
        /** Transient service errors: the phrase is retried once (only used on API 33+). */
        @SuppressLint("InlinedApi")
        val RETRYABLE = setOf(
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_AUDIO,
        )
        /** Writers feeding audio to the recognizer; they live outside the transcribing coroutine. */
        val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        /** A single active recognizer in the whole process. */
        val lock = Mutex()
    }
}
