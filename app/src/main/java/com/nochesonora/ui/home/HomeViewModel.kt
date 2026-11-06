package com.nochesonora.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nochesonora.AppContainer
import com.nochesonora.data.db.SessionStatus
import com.nochesonora.data.db.SessionSummaryRow
import com.nochesonora.data.settings.AppSettings
import com.nochesonora.service.LiveSnapshot
import com.nochesonora.service.NightSessionService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    val live: StateFlow<LiveSnapshot> = container.liveState.snapshot

    val settings: StateFlow<AppSettings?> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val lastSession: StateFlow<SessionSummaryRow?> = container.repository.observeSummaries()
        .map { list -> list.firstOrNull { it.session.status != SessionStatus.IN_PROGRESS } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun freeBytes(): Long = container.repository.freeBytes()

    fun markBatteryPromptShown() {
        viewModelScope.launch { container.settings.setBatteryPromptShown() }
    }

    fun start(context: Context) {
        if (live.value.serviceRunning) return
        NightSessionService.start(context)
    }

    fun stop(context: Context) {
        NightSessionService.stop(context)
    }

    fun pause(context: Context) = NightSessionService.pause(context)

    fun resume(context: Context) = NightSessionService.resume(context)

    companion object {
        const val MIN_FREE_BYTES = 500L * 1024 * 1024
    }
}
