package com.nochesonora.core.classification

/** Classifies a window of 15,600 float samples. The real implementation is YAMNet (MediaPipe). */
fun interface WindowClassifier {
    fun classify(window: FloatArray): List<LabelScore>
}

/** Full layer 3 over an event's PCM: 50 % windowing, YAMNet, heuristic and voting. */
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
