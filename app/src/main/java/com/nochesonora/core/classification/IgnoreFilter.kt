package com.nochesonora.core.classification

/**
 * Sounds the user asked to ignore from a clip ("Ignore this sound": the dog, the air
 * conditioner, the fridge…). An OTHER clip of an ignored type is not saved, unless speech,
 * snoring or a cough can also be heard in it.
 *
 * Pure logic (no android.*), tested on the JVM.
 */
object IgnoreFilter {
    /**
     * Types not offered for ignoring: their label is already a real sound (snoring, cough), so
     * the filter would never discard them and the button would be misleading.
     */
    private val NOT_IGNORABLE = setOf(SoundType.SNORING, SoundType.COUGH)

    /** Types that sound like speech by nature: for them only snoring and cough protect the clip. */
    private val SPEECH_LIKE = setOf(SoundType.VOICES, SoundType.MUSIC_TV, SoundType.LAUGHTER)

    fun canIgnore(type: SoundType?): Boolean = type != null && type !in NOT_IGNORABLE

    /** The ignored type of this clip, or null if it must be saved. */
    fun ignoredType(result: ClassificationResult, ignored: Set<SoundType>): SoundType? {
        if (ignored.isEmpty() || result.category != Category.OTHER) return null
        val type = SoundTypes.ofEvent(result.otherLabel, result.topLabels.map { it.label }) ?: return null
        if (type !in ignored || !canIgnore(type)) return null
        val protect = if (type in SPEECH_LIKE) RepeatFilter.REAL_KINDS - LabelKind.SPEECH else RepeatFilter.REAL_KINDS
        return if (RepeatFilter.hasRealSound(result, protect)) null else type
    }
}
