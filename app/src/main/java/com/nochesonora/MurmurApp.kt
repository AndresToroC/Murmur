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
        // Al abrir la app: sesiones huérfanas → INTERRUMPIDA (§6.6) y retención (A14).
        container.appScope.launch {
            runCatching {
                val repo = container.repository
                repo.markOrphansInterrupted(container.processStartedAt, container.liveState.activeSessionId)
                repo.applyRetention(container.settings.current().retention)
                redoOldTranscripts(repo)
            }.onFailure { Log.e("MurmurApp", "Mantenimiento inicial fallido", it) }
            // El widget y el botón de ajustes rápidos muestran el estado real al abrir la app.
            com.nochesonora.service.SessionSurfaces.refresh(this@MurmurApp)
        }
    }

    /**
     * Las transcripciones hechas con una versión anterior del proceso (0.5.0–0.5.1: sin recortar
     * silencios ni subir el volumen) se borran una vez, para que se rehagan solas al abrir la noche.
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
        /** Súbelo cuando cambie cómo se transcribe, para rehacer las transcripciones viejas. */
        const val TRANSCRIPT_PIPELINE = 2
    }
}
