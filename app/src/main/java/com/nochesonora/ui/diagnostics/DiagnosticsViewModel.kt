package com.nochesonora.ui.diagnostics

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nochesonora.AppContainer
import com.nochesonora.R
import com.nochesonora.audio.AudioCapture
import com.nochesonora.audio.YamnetClassifier
import com.nochesonora.core.audio.Pcm
import com.nochesonora.core.classification.ClassificationResult
import com.nochesonora.core.classification.ClipAnalyzer
import com.nochesonora.service.LiveSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext

sealed interface TestState {
    data object Idle : TestState
    data class Recording(val progress: Float) : TestState
    data object Classifying : TestState
    data class Done(val meanLevelDb: Float, val result: ClassificationResult, val source: String) : TestState
    /** [messageRes] takes an optional [detail] argument (%1$s). */
    data class Failed(@StringRes val messageRes: Int, val detail: String? = null) : TestState
}

class DiagnosticsViewModel(private val container: AppContainer) : ViewModel() {
    val live: StateFlow<LiveSnapshot> = container.liveState.snapshot

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test: StateFlow<TestState> = _test.asStateFlow()

    val isTesting: Boolean get() = _test.value is TestState.Recording || _test.value == TestState.Classifying

    /** Records 10 s in the foreground, classifies with 50 % overlapping windows and saves nothing. */
    fun runTest() {
        if (isTesting || live.value.serviceRunning) return
        _test.value = TestState.Recording(0f)
        viewModelScope.launch {
            try {
                val (pcm, source) = withContext(Dispatchers.IO) { record() } ?: run {
                    _test.value = TestState.Failed(R.string.diag_mic_failed)
                    return@launch
                }
                _test.value = TestState.Classifying
                val result = withContext(Dispatchers.Default) {
                    YamnetClassifier.create(container.appContext).use { ClipAnalyzer(it).analyze(pcm) }
                }
                _test.value = TestState.Done(Pcm.rmsDb(pcm), result, source)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Test failed", e)
                _test.value = TestState.Failed(R.string.diag_error, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun record(): Pair<ShortArray, String>? {
        val capture = AudioCapture(container.appContext)
        if (!capture.open()) return null
        try {
            val total = TEST_SECONDS * Pcm.SAMPLE_RATE
            val pcm = ShortArray(total)
            val block = ShortArray(AudioCapture.BLOCK_SAMPLES)
            var filled = 0
            while (filled < total) {
                currentCoroutineContext().ensureActive()
                val n = capture.readBlock(block)
                if (n < 0) return null
                val copy = minOf(n, total - filled)
                System.arraycopy(block, 0, pcm, filled, copy)
                filled += copy
                _test.value = TestState.Recording(filled.toFloat() / total)
            }
            return pcm to capture.sourceName
        } finally {
            capture.release()
        }
    }

    private companion object {
        const val TAG = "Diagnostics"
        const val TEST_SECONDS = 10
    }
}
