package com.nochesonora.audio

import android.content.Context
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import com.nochesonora.core.audio.Pcm
import com.nochesonora.core.classification.LabelScore
import com.nochesonora.core.classification.WindowClassifier
import com.nochesonora.core.classification.Windowing
import java.io.Closeable

/**
 * YAMNet via MediaPipe Tasks Audio. Each call receives exactly one 15,600-sample window,
 * so it returns a single result (the 50 % overlap windowing is done by [Windowing]).
 * Not thread-safe: one instance per worker.
 */
class YamnetClassifier private constructor(private val classifier: AudioClassifier) : WindowClassifier, Closeable {
    private val audioData = AudioData.create(
        AudioData.AudioDataFormat.builder().setNumOfChannels(1).setSampleRate(Pcm.SAMPLE_RATE.toFloat()).build(),
        Windowing.WINDOW_SAMPLES,
    )

    override fun classify(window: FloatArray): List<LabelScore> {
        audioData.load(window)
        val result = classifier.classify(audioData)
        val categories = result.classificationResults().firstOrNull()
            ?.classifications()?.firstOrNull()
            ?.categories()
            .orEmpty()
        return categories.map { LabelScore(it.categoryName(), it.score()) }
    }

    override fun close() {
        classifier.close()
    }

    companion object {
        const val MODEL_ASSET = "yamnet.tflite"

        fun create(context: Context): YamnetClassifier {
            val options = AudioClassifier.AudioClassifierOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
                .setRunningMode(RunningMode.AUDIO_CLIPS)
                .setMaxResults(10)
                .setScoreThreshold(0.05f)
                .build()
            return YamnetClassifier(AudioClassifier.createFromOptions(context, options))
        }
    }
}
