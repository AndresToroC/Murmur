package com.nochesonora.service

import com.nochesonora.core.classification.Category
import com.nochesonora.core.classification.LabelScore
import com.nochesonora.core.classification.SoundType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LivePhase { CALIBRANDO, ESCUCHANDO, EVENTO, PAUSA }

/** Un clip ya clasificado durante la sesión, para la lista "Lo último que se escuchó". */
data class HeardItem(
    val atMs: Long,
    val category: Category,
    /** Etiqueta YAMNet principal del clip. */
    val label: String?,
    /** true si se descartó como fondo (no se guardó). */
    val discarded: Boolean,
    /** true si era un sonido real pero no se pudo guardar. */
    val saveFailed: Boolean = false,
    /** true si era un ruido ambiental ya guardado esta noche: se sumó como repetición, sin clip. */
    val repeated: Boolean = false,
    /** El tipo de sonido si no se guardó porque la persona lo marcó como ignorado. */
    val ignoredType: SoundType? = null,
)

data class LiveSnapshot(
    /** true desde que se pide iniciar hasta que el servicio termina de cerrar la sesión. */
    val serviceRunning: Boolean = false,
    val sessionId: Long? = null,
    val startedAt: Long = 0L,
    val phase: LivePhase = LivePhase.CALIBRANDO,
    val levelDb: Float = -100f,
    val baselineDb: Float? = null,
    val thresholdDb: Float? = null,
    val calibrationProgress: Float = 0f,
    val counts: Map<Category, Int> = emptyMap(),
    /** Top-3 del último clip clasificado (incluye los descartados como FONDO). */
    val lastTop3: List<LabelScore> = emptyList(),
    val lastCategory: Category? = null,
    val lastDiscarded: Boolean = false,
    val pauseReason: String? = null,
    val audioSource: String = "",
    val classifierAvailable: Boolean = true,
    val droppedEvents: Int = 0,
    /** Los últimos clips clasificados, el más nuevo primero (máximo [RECENT_MAX]). */
    val recent: List<HeardItem> = emptyList(),
    /** Clips descartados como fondo en esta sesión. */
    val discardedCount: Int = 0,
    /** Ruidos ambientales repetidos que no se volvieron a guardar en esta sesión. */
    val repeatedCount: Int = 0,
    /** Sonidos de la lista de ignorados que no se guardaron en esta sesión. */
    val ignoredCount: Int = 0,
    /** Lo que YAMNet oye como fondo al calibrar (p. ej. "Mechanical fan"). */
    val backgroundLabel: String? = null,
    /** Sonidos reales que no se pudieron guardar o procesar. Debe ser 0. */
    val saveErrors: Int = 0,
    val lastError: String? = null,
) {
    companion object {
        const val RECENT_MAX = 6
    }

    val totalEvents: Int get() = counts.values.sum()
}

/** Estado en vivo de la sesión. Singleton del proceso (sin binding al servicio, §6.7). */
class LiveState {
    private val state = MutableStateFlow(LiveSnapshot())
    val snapshot: StateFlow<LiveSnapshot> = state.asStateFlow()

    val activeSessionId: Long? get() = state.value.sessionId

    fun update(transform: (LiveSnapshot) -> LiveSnapshot) = state.update(transform)

    fun reset() {
        state.value = LiveSnapshot()
    }
}
