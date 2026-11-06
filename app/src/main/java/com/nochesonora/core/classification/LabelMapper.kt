package com.nochesonora.core.classification

/** Tipo de etiqueta antes de resolver las reglas que dependen del clip completo. */
enum class LabelKind {
    SNORE,
    /** `Breathing`: RONQUIDO solo si el clip es periódico (A4). */
    BREATHING,
    SPEECH,
    COUGH,
    BACKGROUND,
    /** Motor: FONDO solo si es sostenido (A3). */
    ENGINE,
    OTHER,
}

/** Etiqueta YAMNet (nombre exacto de `categoryName()`) → tipo. */
object LabelMapper {
    const val UNKNOWN_LABEL = "Desconocido"

    private val kinds: Map<String, LabelKind> = buildMap {
        listOf("Snoring", "Gasp").forEach { put(it, LabelKind.SNORE) }
        put("Breathing", LabelKind.BREATHING)
        listOf(
            "Speech", "Conversation", "Whispering", "Babbling", "Narration, monologue", "Shout", "Laughter",
        ).forEach { put(it, LabelKind.SPEECH) }
        listOf("Cough", "Sneeze", "Throat clearing").forEach { put(it, LabelKind.COUGH) }
        listOf(
            "Mechanical fan", "Air conditioning", "Hum", "White noise", "Pink noise", "Silence", "Static",
        ).forEach { put(it, LabelKind.BACKGROUND) }
        listOf("Engine", "Idling", "Engine starting").forEach { put(it, LabelKind.ENGINE) }
    }

    fun kind(label: String): LabelKind = kinds[label] ?: LabelKind.OTHER

    /** Categoría directa de las etiquetas que no dependen del contexto. */
    fun directCategory(kind: LabelKind): Category? = when (kind) {
        LabelKind.SNORE -> Category.RONQUIDO
        LabelKind.SPEECH -> Category.HABLA
        LabelKind.COUGH -> Category.TOS
        LabelKind.BACKGROUND -> Category.FONDO
        LabelKind.OTHER -> Category.OTRO
        LabelKind.BREATHING, LabelKind.ENGINE -> null
    }
}
