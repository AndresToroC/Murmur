package com.nochesonora.service

import com.nochesonora.core.classification.Category
import com.nochesonora.core.classification.LabelScore
import com.nochesonora.core.classification.SoundType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LivePhase { CALIBRATING, LISTENING, EVENT, PAUSED }

/** A clip already classified during the session, for the "Recently heard" list. */
data class HeardItem(
    val atMs: Long,
    val category: Category,
    /** The clip's main YAMNet label. */
    val label: String?,
    /** true if it was discarded as background (not saved). */
    val discarded: Boolean,
    /** true if it was a real sound but could not be saved. */
    val saveFailed: Boolean = false,
    /** true if it was an ambient noise already saved tonight: counted as a repeat, with no clip. */
    val repeated: Boolean = false,
    /** The sound type, if it was not saved because the user marked it as ignored. */
    val ignoredType: SoundType? = null,
)

data class LiveSnapshot(
    /** true from the start request until the service finishes closing the session. */
    val serviceRunning: Boolean = false,
    val sessionId: Long? = null,
    val startedAt: Long = 0L,
    val phase: LivePhase = LivePhase.CALIBRATING,
    val levelDb: Float = -100f,
    val baselineDb: Float? = null,
    val thresholdDb: Float? = null,
    val calibrationProgress: Float = 0f,
    val counts: Map<Category, Int> = emptyMap(),
    /** Top 3 of the last classified clip (includes those discarded as FONDO). */
    val lastTop3: List<LabelScore> = emptyList(),
    val lastCategory: Category? = null,
    val lastDiscarded: Boolean = false,
    val pauseReason: String? = null,
    val audioSource: String = "",
    val classifierAvailable: Boolean = true,
    val droppedEvents: Int = 0,
    /** The latest classified clips, newest first (at most [RECENT_MAX]). */
    val recent: List<HeardItem> = emptyList(),
    /** Clips discarded as background in this session. */
    val discardedCount: Int = 0,
    /** Repeated ambient noises that were not saved again in this session. */
    val repeatedCount: Int = 0,
    /** Sounds on the ignore list that were not saved in this session. */
    val ignoredCount: Int = 0,
    /** What YAMNet hears as background during calibration (e.g. "Mechanical fan"). */
    val backgroundLabel: String? = null,
    /** Real sounds that could not be saved or processed. Should be 0. */
    val saveErrors: Int = 0,
    val lastError: String? = null,
) {
    companion object {
        const val RECENT_MAX = 6
    }

    val totalEvents: Int get() = counts.values.sum()
}

/** Live session state. Process-wide singleton (no service binding, §6.7). */
class LiveState {
    private val state = MutableStateFlow(LiveSnapshot())
    val snapshot: StateFlow<LiveSnapshot> = state.asStateFlow()

    val activeSessionId: Long? get() = state.value.sessionId

    fun update(transform: (LiveSnapshot) -> LiveSnapshot) = state.update(transform)

    fun reset() {
        state.value = LiveSnapshot()
    }
}
