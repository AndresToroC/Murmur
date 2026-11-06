package com.nochesonora.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nochesonora.AppContainer
import com.nochesonora.core.classification.SoundType
import com.nochesonora.core.detection.Sensitivity
import com.nochesonora.data.Retention
import com.nochesonora.data.settings.AppSettings
import com.nochesonora.data.settings.Language
import com.nochesonora.service.LiveSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StorageInfo(val usedBytes: Long, val freeBytes: Long)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings?> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val live: StateFlow<LiveSnapshot> = container.liveState.snapshot

    private val _storage = MutableStateFlow<StorageInfo?>(null)
    val storage: StateFlow<StorageInfo?> = _storage.asStateFlow()

    init {
        refreshStorage()
    }

    fun refreshStorage() {
        viewModelScope.launch {
            _storage.value = withContext(Dispatchers.IO) {
                StorageInfo(container.repository.usedBytes(), container.repository.freeBytes())
            }
        }
    }

    fun setSensitivity(value: Sensitivity) {
        viewModelScope.launch { container.settings.setSensitivity(value) }
    }

    fun setSaveFull(value: Boolean) {
        viewModelScope.launch { container.settings.setSaveFullRecording(value) }
    }

    fun setMorningSummary(value: Boolean) {
        viewModelScope.launch { container.settings.setMorningSummary(value) }
    }

    fun setTranscribeSpeech(value: Boolean) {
        viewModelScope.launch { container.settings.setTranscribeSpeech(value) }
    }

    /** Changing the language redoes the transcriptions (in the new language) when each night is opened. */
    fun setTranscriptionLanguage(value: Language?) {
        viewModelScope.launch {
            if (container.settings.current().transcriptionLanguage == value) return@launch
            container.settings.setTranscriptionLanguage(value)
            container.repository.clearTranscripts()
        }
    }

    fun setGroupRepeats(value: Boolean) {
        viewModelScope.launch { container.settings.setGroupRepeats(value) }
    }

    fun setIgnored(type: SoundType, ignored: Boolean) {
        viewModelScope.launch { container.settings.setIgnored(type, ignored) }
    }

    fun setRetention(value: Retention) {
        viewModelScope.launch {
            container.settings.setRetention(value)
            container.repository.applyRetention(value)
            refreshStorage()
        }
    }

    fun deleteAll() {
        if (container.liveState.snapshot.value.serviceRunning) return
        viewModelScope.launch {
            container.repository.deleteAll()
            refreshStorage()
        }
    }
}
