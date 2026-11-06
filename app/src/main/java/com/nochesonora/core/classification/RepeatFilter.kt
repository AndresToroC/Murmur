package com.nochesonora.core.classification

/**
 * Ruidos del ambiente que se repiten toda la noche (el aire del ventilador sobre el micrófono,
 * viento, lluvia, ruido del cuarto). Se guarda el primero y los siguientes del mismo tipo se suman
 * a él como repeticiones, sin guardar otro clip, mientras no pase [windowMs] sin oírlo.
 *
 * Nunca se pliega un clip en el que YAMNet también oyó habla, ronquido o tos: ese se guarda.
 * Lógica pura (sin android.*), probada en la JVM. Una instancia por sesión; no es thread-safe
 * (la usa solo el hilo de clasificación).
 */
class RepeatFilter(private val windowMs: Long = WINDOW_MS) {
    private class Last(val eventId: Long, var lastAt: Long)

    private val last = HashMap<SoundType, Last>()

    /**
     * El evento guardado al que se suma este como repetición, o null si hay que guardarlo.
     * Si devuelve un id, cuenta este momento como la última vez que se oyó.
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

    /** El evento [eventId] de tipo [type] se guardó: los siguientes del mismo tipo se suman a él. */
    fun saved(type: SoundType?, eventId: Long, atMs: Long) {
        if (type != null) last[type] = Last(eventId, atMs)
    }

    companion object {
        /** Una hora sin oírlo y se vuelve a guardar un clip. */
        const val WINDOW_MS = 60 * 60_000L

        val AMBIENT: Set<SoundType> = setOf(SoundType.WIND, SoundType.FAN, SoundType.RAIN, SoundType.ROOM)

        /** Score medio desde el que una etiqueta de habla, ronquido o tos cuenta como "se oyó". */
        const val REAL_SOUND_MEAN = 0.04f
        val REAL_KINDS: Set<LabelKind> = setOf(LabelKind.SNORE, LabelKind.SPEECH, LabelKind.COUGH)

        /** true si en el clip también se oyó (con [REAL_SOUND_MEAN] o más) alguno de [kinds]. */
        fun hasRealSound(result: ClassificationResult, kinds: Set<LabelKind> = REAL_KINDS): Boolean =
            result.meanScores.any { it.score >= REAL_SOUND_MEAN && LabelMapper.kind(it.label) in kinds }

        /** Tipo de ruido ambiental del clip, o null si no lo es (o si también tiene un sonido real). */
        fun ambientType(result: ClassificationResult): SoundType? {
            if (result.category != Category.OTRO) return null
            val type = SoundTypes.of(result.otherLabel)?.takeIf { it in AMBIENT } ?: return null
            return if (hasRealSound(result)) null else type
        }
    }
}
