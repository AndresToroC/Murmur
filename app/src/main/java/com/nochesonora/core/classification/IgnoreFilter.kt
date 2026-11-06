package com.nochesonora.core.classification

/**
 * Sonidos que la persona pidió ignorar desde un clip ("Ignorar este sonido": el perro, el aire
 * acondicionado, la nevera…). Un clip OTRO de un tipo ignorado no se guarda, salvo que en él
 * también se oiga habla, ronquido o tos.
 *
 * Lógica pura (sin android.*), probada en la JVM.
 */
object IgnoreFilter {
    /**
     * Tipos que no se ofrecen para ignorar: su etiqueta ya es un sonido real (ronquido, tos), así
     * que el filtro nunca los descartaría y el botón engañaría.
     */
    private val NOT_IGNORABLE = setOf(SoundType.SNORING, SoundType.COUGH)

    /** Tipos que de por sí suenan a habla: en ellos solo protegen el ronquido y la tos. */
    private val SPEECH_LIKE = setOf(SoundType.VOICES, SoundType.MUSIC_TV, SoundType.LAUGHTER)

    fun canIgnore(type: SoundType?): Boolean = type != null && type !in NOT_IGNORABLE

    /** El tipo ignorado de este clip, o null si hay que guardarlo. */
    fun ignoredType(result: ClassificationResult, ignored: Set<SoundType>): SoundType? {
        if (ignored.isEmpty() || result.category != Category.OTRO) return null
        val type = SoundTypes.ofEvent(result.otherLabel, result.topLabels.map { it.label }) ?: return null
        if (type !in ignored || !canIgnore(type)) return null
        val protect = if (type in SPEECH_LIKE) RepeatFilter.REAL_KINDS - LabelKind.SPEECH else RepeatFilter.REAL_KINDS
        return if (RepeatFilter.hasRealSound(result, protect)) null else type
    }
}
