package com.nochesonora.core.classification

/**
 * Ambient noises that repeat all night (fan air blowing on the microphone, wind, rain,
 * room noise). The first one is saved and later ones of the same type are added to it as
 * repeats, without saving another clip, as long as [windowMs] does not pass without hearing it.
 *
 * A clip in which YAMNet also heard speech, snoring or a cough is never folded: that one is saved.
 * Pure logic (no android.*), tested on the JVM. One instance per session; not thread-safe
 * (only the classification thread uses it).
 */
class RepeatFilter(private val windowMs: Long = WINDOW_MS) {
    private class Last(val eventId: Long, var lastAt: Long)

    private val last = HashMap<SoundType, Last>()

    /**
     * The saved event this one is added to as a repeat, or null if it must be saved.
     * If it returns an id, this moment counts as the last time it was heard.
     */
    fun foldInto(type: SoundType?, atMs: Long): Long? {
        if (type == null) return null
        val l = last[type] ?: return null
        if (atMs - l.lastAt > windowMs) {
            last.remove(type)
            return null
        }
        l.lastAt = atMs
        return l.eventId
    }

    /** Event [eventId] of type [type] was saved: later ones of the same type are added to it. */
    fun saved(type: SoundType?, eventId: Long, atMs: Long) {
        if (type != null) last[type] = Last(eventId, atMs)
    }

    companion object {
        /** After an hour without hearing it, a clip is saved again. */
        const val WINDOW_MS = 60 * 60_000L

        val AMBIENT: Set<SoundType> = setOf(SoundType.WIND, SoundType.FAN, SoundType.RAIN, SoundType.ROOM)

        /** Mean score at which a speech, snore or cough label counts as "heard". */
        const val REAL_SOUND_MEAN = 0.04f
        val REAL_KINDS: Set<LabelKind> = setOf(LabelKind.SNORE, LabelKind.SPEECH, LabelKind.COUGH)

        /** true if any of [kinds] was also heard in the clip (with [REAL_SOUND_MEAN] or more). */
        fun hasRealSound(result: ClassificationResult, kinds: Set<LabelKind> = REAL_KINDS): Boolean =
            result.meanScores.any { it.score >= REAL_SOUND_MEAN && LabelMapper.kind(it.label) in kinds }

        /** Ambient-noise type of the clip, or null if it is not one (or if it also has a real sound). */
        fun ambientType(result: ClassificationResult): SoundType? {
            if (result.category != Category.OTHER) return null
            val type = SoundTypes.of(result.otherLabel)?.takeIf { it in AMBIENT } ?: return null
            return if (hasRealSound(result)) null else type
        }
    }
}
