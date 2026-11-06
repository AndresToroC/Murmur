package com.nochesonora.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nochesonora.MurmurApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground Service de tipo `microphone` que mantiene viva la sesión toda la noche (§6).
 * Siempre se inicia desde la UI en primer plano: Android 14+ no permite arrancar un FGS de
 * micrófono desde segundo plano, por eso no se usa START_STICKY (A10).
 */
class NightSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sessionJob: Job? = null
    @Volatile private var engine: SessionEngine? = null
    @Volatile private var stopPending = false
    /** Pausa pedida antes de que el motor exista (p. ej. un toque muy rápido en la notificación). */
    @Volatile private var pausePending = false
    private var lastNotifiedPause: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val container get() = (application as MurmurApp).container

    // Notificación en el idioma elegido en Ajustes, no en el del sistema.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.nochesonora.data.settings.AppLanguage.wrap(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopPending = true
                engine?.requestStop()
                if (sessionJob == null) stopSelf()
            }
            // Sin sesión (una notificación vieja): no hay nada que pausar.
            ACTION_PAUSE, ACTION_RESUME -> if (sessionJob == null) stopSelf() else setUserPaused(intent.action == ACTION_PAUSE)
            else -> if (sessionJob == null) startSession()
        }
        return START_NOT_STICKY
    }

    private fun startSession() {
        val startedAt = System.currentTimeMillis()
        // startForeground antes de cualquier otra cosa (§6.2).
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(
                this,
                SessionNotification.NOTIFICATION_ID,
                SessionNotification.build(this, startedAt, 0, null),
                type,
            )
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo pasar a primer plano", e)
            stopSelf()
            return
        }
        container.liveState.update { LiveSnapshot(serviceRunning = true, startedAt = startedAt) }
        sessionJob = scope.launch { runSession(startedAt) }
    }

    private fun setUserPaused(paused: Boolean) {
        pausePending = paused
        engine?.setUserPaused(paused)
    }

    private suspend fun runSession(startedAt: Long) {
        val c = container
        var sessionId: Long? = null
        var ignoredWatch: Job? = null
        acquireWakeLock()
        try {
            val settings = c.settings.current()
            c.repository.markOrphansInterrupted(c.processStartedAt, activeSessionId = null)
            val session = c.repository.createSession(startedAt, settings.sensitivity.marginDb, settings.saveFullRecording)
            sessionId = session.id
            c.liveState.update { it.copy(sessionId = session.id, startedAt = startedAt, phase = LivePhase.CALIBRANDO) }
            val engine = SessionEngine(
                context = applicationContext,
                repository = c.repository,
                liveState = c.liveState,
                onStatusChanged = { events, pauseReason ->
                    SessionNotification.update(this, startedAt, events, pauseReason)
                    // El botón de ajustes rápidos y el widget muestran si está en pausa.
                    if (pauseReason != lastNotifiedPause) {
                        lastNotifiedPause = pauseReason
                        SessionSurfaces.refresh(this)
                    }
                },
            )
            this.engine = engine
            engine.setIgnoredSounds(settings.ignoredSounds)
            // Si se marca un sonido como ignorado en plena noche, cuenta desde ese momento.
            ignoredWatch = scope.launch {
                c.settings.settings.map { it.ignoredSounds }.distinctUntilChanged().collect { engine.setIgnoredSounds(it) }
            }
            if (stopPending) engine.requestStop()
            if (pausePending) engine.setUserPaused(true)
            SessionSurfaces.refresh(this)
            engine.run(session, settings.sensitivity, groupRepeats = settings.groupRepeats)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "La sesión terminó con un error", e)
        } finally {
            ignoredWatch?.cancel()
            withContext(NonCancellable) {
                sessionId?.let { id ->
                    runCatching { c.repository.completeSession(id, System.currentTimeMillis()) }
                    postSummary(id)
                }
                runCatching { c.repository.applyRetention(c.settings.current().retention) }
                c.liveState.reset()
                engine = null
                releaseWakeLock()
                SessionSurfaces.refresh(this@NightSessionService)
                withContext(Dispatchers.Main) {
                    ServiceCompat.stopForeground(this@NightSessionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    /** Resumen al despertar (si está activado en Ajustes). Nunca debe impedir cerrar la sesión. */
    private suspend fun postSummary(sessionId: Long) {
        runCatching {
            if (!container.settings.current().morningSummary) return
            val row = container.repository.observeSummaries().first().firstOrNull { it.session.id == sessionId } ?: return
            NightSummaryNotification.post(this, row)
        }.onFailure { Log.w(TAG, "No se pudo publicar el resumen", it) }
    }

    @SuppressLint("WakelockTimeout") // la sesión dura toda la noche; se libera en finally
    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Deslizar la app en "recientes" no debe detener la sesión.
    }

    override fun onDestroy() {
        engine?.requestStop()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.nochesonora.action.START"
        const val ACTION_STOP = "com.nochesonora.action.STOP"
        const val ACTION_PAUSE = "com.nochesonora.action.PAUSE"
        const val ACTION_RESUME = "com.nochesonora.action.RESUME"
        private const val TAG = "NightSessionService"
        private const val WAKE_LOCK_TAG = "Murmur:session"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NightSessionService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NightSessionService::class.java).setAction(ACTION_STOP))
        }

        fun pause(context: Context) {
            context.startService(Intent(context, NightSessionService::class.java).setAction(ACTION_PAUSE))
        }

        fun resume(context: Context) {
            context.startService(Intent(context, NightSessionService::class.java).setAction(ACTION_RESUME))
        }
    }
}
