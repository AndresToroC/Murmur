package com.nochesonora.core.classification

data class ClassificationResult(
    val category: Category,
    val confidence: Float,
    /** Original YAMNet label when the category is OTRO. */
    val otherLabel: String?,
    /** The 3 labels with the highest mean score across windows. */
    val topLabels: List<LabelScore>,
    /** All labels with their mean score, highest first (for the Diagnostics screen). */
    val meanScores: List<LabelScore>,
    val snore: SnoreResult,
    val windowCount: Int,
)

/** Weighted vote over a clip's windows (plan §4.5). */
object VotingClassifier {
    const val MIN_SCORE = 0.30f
    const val ENGINE_SUSTAINED_FRACTION = 0.70f
    const val SNORE_BOOST_FACTOR = 1.5f
    const val SNORE_BOOST_PER_WINDOW = 0.3f
    private val REAL_SOUNDS = listOf(Category.RONQUIDO, Category.HABLA, Category.TOS)

    fun classify(windows: List<List<LabelScore>>, snore: SnoreResult): ClassificationResult {
        val n = windows.size.coerceAtLeast(1)
        val scores = FloatArray(Category.entries.size)
        val otherWeights = HashMap<String, Float>()
        val engineWeights = HashMap<String, Float>()
        var engineScore = 0f
        var engineWindows = 0

        for (window in windows) {
            var windowHasEngine = false
            for ((label, score) in window) {
                if (score < MIN_SCORE) continue
                when (val kind = LabelMapper.kind(label)) {
                    LabelKind.BREATHING -> if (snore.isPeriodic) {
                        scores[Category.RONQUIDO.ordinal] += score
                    } else {
                        scores[Category.OTRO.ordinal] += score
                        otherWeights.merge(label, score, Float::plus)
                    }
                    LabelKind.ENGINE -> {
                        engineScore += score
                        engineWeights.merge(label, score, Float::plus)
                        windowHasEngine = true
                    }
                    else -> {
                        val category = LabelMapper.directCategory(kind)!!
                        scores[category.ordinal] += score
                        if (category == Category.OTRO) otherWeights.merge(label, score, Float::plus)
                    }
                }
            }
            if (windowHasEngine) engineWindows++
        }

        // Sustained engine = background; brief engine = other (A3).
        if (engineScore > 0f) {
            if (engineWindows >= ENGINE_SUSTAINED_FRACTION * n) {
                scores[Category.FONDO.ordinal] += engineScore
            } else {
                scores[Category.OTRO.ordinal] += engineScore
                engineWeights.forEach { (label, w) -> otherWeights.merge(label, w, Float::plus) }
            }
        }

        if (snore.isSnoreLike) {
            scores[Category.RONQUIDO.ordinal] = scores[Category.RONQUIDO.ordinal] * SNORE_BOOST_FACTOR + SNORE_BOOST_PER_WINDOW * n
        }

        // Background never beats a real sound. A cough fills 1–2 windows and the rest of the clip
        // (pre/post-roll) is "Silence" or a fan: summing windows, FONDO would win and the cough would be lost.
        if (REAL_SOUNDS.any { scores[it.ordinal] > 0f }) scores[Category.FONDO.ordinal] = 0f

        val meanScores = meanScores(windows, n)
        val top3 = meanScores.take(3)

        var best = -1
        for (i in scores.indices) if (scores[i] > 0f && (best < 0 || scores[i] > scores[best])) best = i
        if (best < 0) {
            // No votes: the event passed the energy threshold, so it is saved as OTRO (A5).
            return ClassificationResult(Category.OTRO, 0f, LabelMapper.UNKNOWN_LABEL, top3, meanScores, snore, windows.size)
        }
        val category = Category.entries[best]
        val otherLabel = if (category == Category.OTRO) {
            otherWeights.maxByOrNull { it.value }?.key ?: LabelMapper.UNKNOWN_LABEL
        } else {
            null
        }
        return ClassificationResult(
            category = category,
            confidence = (scores[best] / n).coerceIn(0f, 1f),
            otherLabel = otherLabel,
            topLabels = top3,
            meanScores = meanScores,
            snore = snore,
            windowCount = windows.size,
        )
    }

    private fun meanScores(windows: List<List<LabelScore>>, n: Int): List<LabelScore> {
        val sums = HashMap<String, Float>()
        for (window in windows) for ((label, score) in window) sums.merge(label, score, Float::plus)
        return sums.map { (label, sum) -> LabelScore(label, sum / n) }
            .sortedWith(compareByDescending<LabelScore> { it.score }.thenBy { it.label })
    }

    /** `[{"label":"Snoring","score":0.62},…]` without depending on a JSON library. */
    fun toJson(labels: List<LabelScore>): String = labels.joinToString(",", "[", "]") { (label, score) ->
        val escaped = label.replace("\\", "\\\\").replace("\"", "\\\"")
        "{\"label\":\"$escaped\",\"score\":${"%.3f".format(java.util.Locale.ROOT, score)}}"
    }

    fun fromJson(json: String): List<LabelScore> {
        val regex = Regex("""\{"label":"((?:[^"\\]|\\.)*)","score":([0-9.eE+-]+)\}""")
        return regex.findAll(json).map { m ->
            val label = m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
            LabelScore(label, m.groupValues[2].toFloatOrNull() ?: 0f)
        }.toList()
    }
}
