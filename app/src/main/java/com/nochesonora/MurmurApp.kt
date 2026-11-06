package com.nochesonora

import android.app.Application
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.launch

class MurmurApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.nochesonora.data.settings.AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // On app open: orphaned sessions → INTERRUPTED (§6.6) and retention (A14).
        container.appScope.launch {
            runCatching {
                val repo = container.repository
                repo.markOrphansInterrupted(container.processStartedAt, container.liveState.activeSessionId)
                repo.applyRetention(container.settings.current().retention)
                redoOldTranscripts(repo)
            }.onFailure { Log.e("MurmurApp", "Startup maintenance failed", it) }
            // The widget and the quick settings tile show the real state when the app opens.
            com.nochesonora.service.SessionSurfaces.refresh(this@MurmurApp)
        }
    }

    /**
     * Transcripts made with an older version of the pipeline are deleted once, so they are redone
     * automatically when the night is opened. 2: silence trimming and volume boost (0.5.2).
     * 3: language shown and selectable, after transcripts came out in English (0.8.1).
     */
    private suspend fun redoOldTranscripts(repo: com.nochesonora.data.NightRepository) {
        val prefs = getSharedPreferences(MAINTENANCE_PREFS, MODE_PRIVATE)
        if (prefs.getInt(KEY_TRANSCRIPT_PIPELINE, 1) >= TRANSCRIPT_PIPELINE) return
        repo.clearTranscripts()
        prefs.edit { putInt(KEY_TRANSCRIPT_PIPELINE, TRANSCRIPT_PIPELINE) }
    }

    private companion object {
        const val MAINTENANCE_PREFS = "maintenance"
        const val KEY_TRANSCRIPT_PIPELINE = "transcript_pipeline"
        /** Bump it when the transcription pipeline changes, so old transcripts are redone. */
        const val TRANSCRIPT_PIPELINE = 3
    }
}
