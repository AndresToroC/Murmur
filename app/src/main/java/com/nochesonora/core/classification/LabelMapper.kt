package com.nochesonora.core.classification

/** Label kind before resolving the rules that depend on the whole clip. */
enum class LabelKind {
    SNORE,
    /** `Breathing`: RONQUIDO only if the clip is periodic (A4). */
    BREATHING,
    SPEECH,
    COUGH,
    BACKGROUND,
    /** Engine: FONDO only if sustained (A3). */
    ENGINE,
    OTHER,
}

/** YAMNet label (exact `categoryName()` name) → kind. */
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

    /** Direct category for labels that do not depend on context. */
    fun directCategory(kind: LabelKind): Category? = when (kind) {
        LabelKind.SNORE -> Category.RONQUIDO
        LabelKind.SPEECH -> Category.HABLA
        LabelKind.COUGH -> Category.TOS
        LabelKind.BACKGROUND -> Category.FONDO
        LabelKind.OTHER -> Category.OTRO
        LabelKind.BREATHING, LabelKind.ENGINE -> null
    }
}
