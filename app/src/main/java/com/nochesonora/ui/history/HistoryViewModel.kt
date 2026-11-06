package com.nochesonora.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nochesonora.AppContainer
import com.nochesonora.data.db.SessionSummaryRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(private val container: AppContainer) : ViewModel() {
    /** null mientras carga. */
    val sessions: StateFlow<List<SessionSummaryRow>?> = container.repository.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggleFavorite(row: SessionSummaryRow) {
        viewModelScope.launch { container.repository.setFavorite(row.session.id, !row.session.isFavorite) }
    }

    fun delete(sessionId: Long) {
        if (container.liveState.activeSessionId == sessionId) return
        viewModelScope.launch { container.repository.deleteSession(sessionId) }
    }
}
