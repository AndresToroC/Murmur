package com.nochesonora.core.classification

/** Clasifica una ventana de 15.600 muestras float. La implementación real es YAMNet (MediaPipe). */
fun interface WindowClassifier {
    fun classify(window: FloatArray): List<LabelScore>
}

/** Capa 3 completa sobre el PCM de un evento: ventaneo al 50 %, YAMNet, heurística y votación. */
class ClipAnalyzer(private val classifier: WindowClassifier) {
    private val window = FloatArray(Windowing.WINDOW_SAMPLES)

    fun analyze(pcm: ShortArray): ClassificationResult {
        val count = Windowing.windowCount(pcm.size)
        val results = ArrayList<List<LabelScore>>(count)
        for (i in 0 until count) {
            Windowing.fillWindow(pcm, i, window)
            results += classifier.classify(window)
        }
        return VotingClassifier.classify(results, SnoreHeuristic.analyze(pcm))
    }
}
