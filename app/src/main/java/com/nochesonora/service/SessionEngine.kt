package com.nochesonora.service

import android.content.Context
import android.os.Process
import android.util.Log
import com.nochesonora.audio.AacFullRecorder
import com.nochesonora.audio.AudioCapture
import com.nochesonora.audio.AudioSource
import com.nochesonora.audio.InterruptionMonitor
import com.nochesonora.audio.YamnetClassifier
import com.nochesonora.core.audio.Pcm
import com.nochesonora.core.classification.Category
import com.nochesonora.core.classification.ClassificationResult
import com.nochesonora.core.classification.ClipAnalyzer
import com.nochesonora.core.classification.IgnoreFilter
import com.nochesonora.core.classification.RepeatFilter
import com.nochesonora.core.classification.SnoreHeuristic
import com.nochesonora.core.classification.SoundType
import com.nochesonora.core.classification.WindowClassifier
import com.nochesonora.core.detection.ClosedEvent
import com.nochesonora.core.detection.DetectorConfig
import com.nochesonora.core.detection.DetectorState
import com.nochesonora.core.detection.EventDetector
import com.nochesonora.core.detection.Sensitivity
import com.nochesonora.data.NightRepository
import com.nochesonora.data.db.PauseReason
import com.nochesonora.data.db.SessionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/** Trabajo para el hilo de clasificación. */
private sealed interface ClassifyJob {
    class Event(val event: ClosedEvent) : ClassifyJob
    /** Audio sin evento: se clasifica solo para mostrar qué fondo hay. */
    class BackgroundProbe(val pcm: ShortArray) : ClassifyJob
}

/**
 * Orquesta captura → detector → clasificador → repositorio durante una sesión (§4).
 *
 * Hilos:
 * - captura: un hilo dedicado que nunca espera a nadie (cola de clasificación con `trySend`);
 * - clasificación: otro hilo dedicado con la única instancia de YAMNet;
 * - base de datos: las escrituras de pausas/base se encolan en orden en una corrutina.
 */
class SessionEngine(
    private val context: Context,
    private val repository: NightRepository,
    private val liveState: LiveState,
    private val onStatusChanged: (events: Int, pauseReason: String?) -> Unit,
    /** Inyectables para probar la sesión completa en la JVM con audio grabado. */
    private val openAudio: () -> AudioSource = { AudioCapture(context) },
    private val loadClassifier: () -> WindowClassifier = { YamnetClassifier.create(context) },
) {
    @Volatile private var stopRequested = false

    /** Pausa pedida por la persona (notificación o Inicio). Mientras dura, el micrófono queda libre. */
    @Volatile private var userPaused = false

    fun requestStop() {
        stopRequested = true
    }

    fun setUserPaused(paused: Boolean) {
        userPaused = paused
    }

    /**
     * Ruidos ambientales ya guardados en esta sesión (viento, ventilador…), para no repetirlos.
     * Solo lo usa el hilo de clasificación. Si el clip guardado se borró, `addRepeat` da false y
     * se guarda uno nuevo, que pasa a ser el de referencia.
     */
    private val repeats = RepeatFilter()
    private var groupRepeats = true

    /** Sonidos que la persona pidió ignorar. Cambia en plena noche si los marca desde un clip. */
    @Volatile private var ignoredSounds: Set<SoundType> = emptySet()

    fun setIgnoredSounds(types: Set<SoundType>) {
        ignoredSounds = types
    }

    suspend fun run(session: SessionEntity, sensitivity: Sensitivity, groupRepeats: Boolean = true) {
        this.groupRepeats = groupRepeats
        val captureDispatcher = singleThread("Murmur-captura")
        val classifyDispatcher = singleThread("Murmur-clasificacion")
        val capture = openAudio()
        val monitor = InterruptionMonitor(context) { capture.audioSessionId }
        val classifier = runCatching { withContext(classifyDispatcher) { loadClassifier() } }
            .onFailure { Log.e(TAG, "No se pudo cargar YAMNet; los eventos se guardarán sin clasificar", it) }
            .getOrNull()
        val aac = session.fullRecordingPath?.let { path ->
            runCatching { AacFullRecorder.open(File(path)) }
                .onFailure { Log.e(TAG, "No se pudo iniciar la grabación completa", it) }
                .getOrNull()
        }
        liveState.update { it.copy(classifierAvailable = classifier != null) }

        try {
            coroutineScope {
                val queue = Channel<ClassifyJob>(capacity = QUEUE_CAPACITY)
                val dbOps = Channel<suspend () -> Unit>(Channel.UNLIMITED)
                val analyzer = classifier?.let { ClipAnalyzer(it) }

                val worker = launch(classifyDispatcher) {
                    for (job in queue) when (job) {
                        is ClassifyJob.Event -> processEvent(session.id, job.event, analyzer)
                        is ClassifyJob.BackgroundProbe -> probeBackground(job.pcm, analyzer)
                    }
                }
                val dbWorker = launch {
                    for (op in dbOps) runCatching { op() }.onFailure { Log.e(TAG, "Escritura fallida", it) }
                }
                val heartbeat = launch {
                    while (isActive) {
                        delay(HEARTBEAT_MS)
                        runCatching { repository.heartbeat(session.id, System.currentTimeMillis()) }
                    }
                }
                monitor.start(this)

                withContext(captureDispatcher) {
                    captureLoop(session.id, sensitivity, capture, monitor, aac, queue, dbOps)
                }

                // Detener: vaciar la cola de clasificación (máximo 10 s) y las escrituras pendientes.
                queue.close()
                if (withTimeoutOrNull(DRAIN_TIMEOUT_MS) { worker.join() } == null) {
                    Log.w(TAG, "La clasificación no terminó a tiempo; se descartan los eventos pendientes")
                    worker.cancelAndJoin() // espera a que termine la inferencia en curso antes de cerrar YAMNet
                }
                heartbeat.cancel()
                monitor.stop()
                dbOps.close()
                dbWorker.join()
            }
        } finally {
            monitor.stop()
            capture.release()
            runCatching { aac?.close() }
            runCatching { (classifier as? Closeable)?.close() }
            captureDispatcher.close()
            classifyDispatcher.close()
        }
    }

    private suspend fun captureLoop(
        sessionId: Long,
        sensitivity: Sensitivity,
        capture: AudioSource,
        monitor: InterruptionMonitor,
        aac: AacFullRecorder?,
        queue: Channel<ClassifyJob>,
        dbOps: Channel<suspend () -> Unit>,
    ) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val detector = EventDetector(DetectorConfig(sensitivityDb = sensitivity.marginDb.toFloat()))
        val buffer = ShortArray(AudioCapture.BLOCK_SAMPLES)
        // Ancla de tiempo de cada tramo continuo: hora = ancla + muestras / 16 (§4.1).
        var anchorMs = System.currentTimeMillis()
        var samplesSinceAnchor = 0L
        var pausedReason: String? = null
        var backoffMs = INITIAL_BACKOFF_MS
        var baselineSaved = false
        var zeroBlocks = 0
        // Identificar el fondo al terminar el calentamiento y otra vez al terminar la calibración.
        var probesDone = 0

        fun enqueue(event: ClosedEvent?) {
            if (event == null) return
            if (!queue.trySend(ClassifyJob.Event(event)).isSuccess) {
                Log.w(TAG, "Cola de clasificación llena: evento descartado")
                liveState.update { it.copy(droppedEvents = it.droppedEvents + 1) }
            }
        }

        fun beginPause(reason: String) {
            val at = System.currentTimeMillis()
            enqueue(detector.interrupt())
            // startPause cierra la pausa anterior si el motivo cambia.
            dbOps.trySend { repository.startPause(sessionId, at, reason) }
            pausedReason = reason
            liveState.update { it.copy(phase = LivePhase.PAUSA, pauseReason = reason, levelDb = Pcm.FLOOR_DB) }
            onStatusChanged(liveState.snapshot.value.totalEvents, reason)
        }

        fun endPause() {
            val at = System.currentTimeMillis()
            dbOps.trySend { repository.endPause(sessionId, at) }
            pausedReason = null
            anchorMs = at
            samplesSinceAnchor = 0
            zeroBlocks = 0
            // No se recalibra: se mantiene la base (§6.5).
            val phase = if (detector.baseline.isCalibrated) LivePhase.ESCUCHANDO else LivePhase.CALIBRANDO
            liveState.update { it.copy(phase = phase, pauseReason = null) }
            onStatusChanged(liveState.snapshot.value.totalEvents, null)
        }

        if (capture.open()) {
            liveState.update { it.copy(audioSource = capture.sourceName) }
        } else {
            beginPause(PauseReason.ERROR_AUDIO)
        }

        while (!stopRequested) {
            currentCoroutineContext().ensureActive()

            // Pausa manual: se cierra el evento abierto y se suelta el micrófono (el indicador verde
            // de Android se apaga). Al continuar se reabre y se sigue con la misma base (§6.5).
            if (userPaused) {
                if (pausedReason != PauseReason.USUARIO) beginPause(PauseReason.USUARIO)
                if (capture.isOpen) capture.release()
                delay(STOP_POLL_MS)
                continue
            }
            if (pausedReason == PauseReason.USUARIO) {
                if (capture.open()) {
                    backoffMs = INITIAL_BACKOFF_MS
                    liveState.update { it.copy(audioSource = capture.sourceName) }
                    endPause()
                } else {
                    beginPause(PauseReason.ERROR_AUDIO)
                }
                continue
            }

            if (!capture.isOpen) {
                // Reintento con espera exponencial: 1, 2, 4… hasta 30 s.
                var waited = 0L
                while (waited < backoffMs && !stopRequested) {
                    delay(STOP_POLL_MS)
                    waited += STOP_POLL_MS
                }
                if (stopRequested) break
                if (capture.open()) {
                    backoffMs = INITIAL_BACKOFF_MS
                    liveState.update { it.copy(audioSource = capture.sourceName) }
                    if (pausedReason == PauseReason.ERROR_AUDIO) endPause()
                } else {
                    backoffMs = minOf(backoffMs * 2, MAX_BACKOFF_MS)
                }
                continue
            }

            val n = capture.readBlock(buffer)
            if (n < 0) {
                Log.w(TAG, "Error de lectura del micrófono: $n")
                capture.release()
                if (pausedReason != PauseReason.ERROR_AUDIO) beginPause(PauseReason.ERROR_AUDIO)
                continue
            }

            // Ceros digitales sostenidos = otra app tiene el micrófono aunque el sistema no lo avise.
            val level = Pcm.rmsDb(buffer, 0, n)
            zeroBlocks = if (level <= Pcm.FLOOR_DB) zeroBlocks + 1 else 0
            val reason = monitor.currentReason
                ?: if (zeroBlocks >= ZERO_BLOCKS_FOR_PAUSE) PauseReason.MICROFONO_OCUPADO else null
            if (reason != null) {
                if (pausedReason != reason) beginPause(reason)
                continue // se sigue leyendo para mantener vivo el AudioRecord, pero se descarta
            }
            if (pausedReason != null) {
                endPause()
                continue // este bloque pertenece al tramo anterior; el nuevo tramo empieza ahora
            }

            val timestamp = anchorMs + samplesSinceAnchor * 1000 / Pcm.SAMPLE_RATE
            samplesSinceAnchor += n
            aac?.feed(buffer, n)

            val out = detector.onBlock(timestamp, buffer, level, 0, n)
            enqueue(out.closedEvent)
            val calibrated = detector.baseline.isCalibrated
            if (!baselineSaved && calibrated && out.baselineDb != null) {
                baselineSaved = true
                val baseline = out.baselineDb
                dbOps.trySend { repository.setBaseline(sessionId, baseline) }
            }
            // Sonda de fondo: solo con el detector en reposo, para no clasificar un evento como fondo.
            val probeDue = (probesDone == 0 && out.thresholdDb != null) || (probesDone == 1 && calibrated)
            if (probeDue && out.state == DetectorState.ESCUCHANDO && out.closedEvent == null) {
                queue.trySend(ClassifyJob.BackgroundProbe(detector.recentAudio(PROBE_MS)))
                probesDone++
            }
            val phase = when {
                out.state == DetectorState.EVENTO -> LivePhase.EVENTO
                !calibrated -> LivePhase.CALIBRANDO
                else -> LivePhase.ESCUCHANDO
            }
            liveState.update {
                it.copy(
                    phase = phase,
                    levelDb = out.levelDb,
                    baselineDb = out.baselineDb,
                    thresholdDb = out.thresholdDb,
                    calibrationProgress = out.calibrationProgress,
                )
            }
        }

        // Fin de sesión: el evento abierto se clasifica y guarda.
        detector.interrupt()?.let { queue.send(ClassifyJob.Event(it)) }
        if (pausedReason != null) {
            val at = System.currentTimeMillis()
            dbOps.trySend { repository.endPause(sessionId, at) }
        }
    }

    private suspend fun processEvent(sessionId: Long, event: ClosedEvent, analyzer: ClipAnalyzer?) {
        try {
            val result = analyzer?.analyze(event.pcm) ?: unclassified(event)
            val discarded = result.category == Category.FONDO
            // Un sonido de la lista de ignorados (el perro, el aire…): ni WAV ni fila.
            val ignoredType = if (discarded) null else IgnoreFilter.ignoredType(result, ignoredSounds)
            val skipped = discarded || ignoredType != null
            // Ruido ambiental ya guardado esta noche (el ventilador, el viento…): se suma como
            // repetición al primero, sin otro clip.
            val ambient = if (groupRepeats && !skipped) RepeatFilter.ambientType(result) else null
            val foldInto = repeats.foldInto(ambient, event.startedAtMs)
            val repeated = foldInto != null && repository.addRepeat(sessionId, foldInto, event.startedAtMs)
            // FONDO se descarta: ni WAV ni fila. Si guardar falla, se muestra en Inicio (nunca en silencio).
            val saved = when {
                skipped || repeated -> true
                else -> repository.saveEvent(sessionId, event, result)
                    ?.also { id -> repeats.saved(ambient, id, event.startedAtMs) } != null
            }
            val heard = HeardItem(
                atMs = event.startedAtMs,
                category = result.category,
                label = result.otherLabel ?: result.topLabels.firstOrNull()?.label,
                discarded = discarded,
                saveFailed = !saved,
                repeated = repeated,
                ignoredType = ignoredType,
            )
            liveState.update {
                it.copy(
                    lastTop3 = result.topLabels,
                    lastCategory = result.category,
                    lastDiscarded = discarded,
                    recent = (listOf(heard) + it.recent).take(LiveSnapshot.RECENT_MAX),
                    discardedCount = it.discardedCount + if (discarded) 1 else 0,
                    repeatedCount = it.repeatedCount + if (repeated) 1 else 0,
                    ignoredCount = it.ignoredCount + if (ignoredType != null) 1 else 0,
                    counts = if (saved && !skipped && !repeated) it.counts + (result.category to (it.counts[result.category] ?: 0) + 1) else it.counts,
                    saveErrors = it.saveErrors + if (saved) 0 else 1,
                    lastError = if (saved) it.lastError else "No se pudo guardar el clip de las ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(event.startedAtMs)}",
                )
            }
            if (saved && !skipped && !repeated) onStatusChanged(liveState.snapshot.value.totalEvents, liveState.snapshot.value.pauseReason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo procesar un evento", e)
            liveState.update {
                it.copy(saveErrors = it.saveErrors + 1, lastError = "Error al procesar un sonido: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
            }
        }
    }

    private fun probeBackground(pcm: ShortArray, analyzer: ClipAnalyzer?) {
        if (analyzer == null || pcm.isEmpty()) return
        runCatching { analyzer.analyze(pcm) }
            .onSuccess { r -> liveState.update { it.copy(backgroundLabel = r.meanScores.firstOrNull()?.label) } }
            .onFailure { Log.w(TAG, "No se pudo identificar el fondo", it) }
    }

    private fun unclassified(event: ClosedEvent) = ClassificationResult(
        category = Category.OTRO,
        confidence = 0f,
        otherLabel = "Sin clasificar",
        topLabels = emptyList(),
        meanScores = emptyList(),
        snore = SnoreHeuristic.analyze(event.pcm),
        windowCount = 0,
    )

    private fun singleThread(name: String): ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, name) }.asCoroutineDispatcher()

    private companion object {
        const val TAG = "SessionEngine"
        // Cada evento ocupa como mucho ~2 MB (65 s): 16 en cola ≈ 32 MB en el peor caso. Con 4 se perdían
        // eventos en ráfagas (plan §12, B7).
        const val QUEUE_CAPACITY = 16
        const val HEARTBEAT_MS = 30_000L
        const val DRAIN_TIMEOUT_MS = 10_000L
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val STOP_POLL_MS = 200L
        const val ZERO_BLOCKS_FOR_PAUSE = 20 // 2 s
        const val PROBE_MS = 5_000L
    }
}
