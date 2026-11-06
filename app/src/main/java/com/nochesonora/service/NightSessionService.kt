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
 * `microphone`-type foreground service that keeps the session alive all night (§6).
 * It is always started from the foreground UI: Android 14+ does not allow starting a microphone
 * FGS from the background, which is why START_STICKY is not used (A10).
 */
class NightSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sessionJob: Job? = null
    @Volatile private var engine: SessionEngine? = null
    @Volatile private var stopPending = false
    /** Pause requested before the engine exists (e.g. a very quick tap on the notification). */
    @Volatile private var pausePending = false
    private var lastNotifiedPause: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val container get() = (application as MurmurApp).container

    // Notification in the language chosen in Settings, not the system one.
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
            // No session (a stale notification): nothing to pause.
            ACTION_PAUSE, ACTION_RESUME -> if (sessionJob == null) stopSelf() else setUserPaused(intent.action == ACTION_PAUSE)
            else -> if (sessionJob == null) startSession()
        }
        return START_NOT_STICKY
    }

    private fun startSession() {
        val startedAt = System.currentTimeMillis()
        // startForeground before anything else (§6.2).
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(
                this,
                SessionNotification.NOTIFICATION_ID,
                SessionNotification.build(this, startedAt, 0, null),
                type,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not move to the foreground", e)
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
            c.liveState.update { it.copy(sessionId = session.id, startedAt = startedAt, phase = LivePhase.CALIBRATING) }
            val engine = SessionEngine(
                context = applicationContext,
                repository = c.repository,
                liveState = c.liveState,
                onStatusChanged = { events, pauseReason ->
                    SessionNotification.update(this, startedAt, events, pauseReason)
                    // The quick settings tile and the widget show whether it is paused.
                    if (pauseReason != lastNotifiedPause) {
                        lastNotifiedPause = pauseReason
                        SessionSurfaces.refresh(this)
                    }
                },
            )
            this.engine = engine
            engine.setIgnoredSounds(settings.ignoredSounds)
            // If a sound is marked as ignored mid-night, it applies from that moment on.
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
            Log.e(TAG, "The session ended with an error", e)
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

    /** Wake-up summary (if enabled in Settings). Must never prevent the session from closing. */
    private suspend fun postSummary(sessionId: Long) {
        runCatching {
            if (!container.settings.current().morningSummary) return
            val row = container.repository.observeSummaries().first().firstOrNull { it.session.id == sessionId } ?: return
            NightSummaryNotification.post(this, row)
        }.onFailure { Log.w(TAG, "Could not post the summary", it) }
    }

    @SuppressLint("WakelockTimeout") // the session lasts all night; released in finally
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
        // Swiping the app away in "recents" must not stop the session.
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
