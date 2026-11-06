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
import com.nochesonora.core.classification.LabelMapper
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

/** Work for the classification thread. */
private sealed interface ClassifyJob {
    class Event(val event: ClosedEvent) : ClassifyJob
    /** Audio with no event: classified only to show what the background is. */
    class BackgroundProbe(val pcm: ShortArray) : ClassifyJob
}

/**
 * Orchestrates capture → detector → classifier → repository during a session (§4).
 *
 * Threads:
 * - capture: a dedicated thread that never waits on anyone (classification queue via `trySend`);
 * - classification: another dedicated thread holding the single YAMNet instance;
 * - database: pause/baseline writes are queued in order on a coroutine.
 */
class SessionEngine(
    private val context: Context,
    private val repository: NightRepository,
    private val liveState: LiveState,
    private val onStatusChanged: (events: Int, pauseReason: String?) -> Unit,
    /** Injectable so the full session can be tested on the JVM with recorded audio. */
    private val openAudio: () -> AudioSource = { AudioCapture(context) },
    private val loadClassifier: () -> WindowClassifier = { YamnetClassifier.create(context) },
) {
    @Volatile private var stopRequested = false

    /** Pause requested by the user (notification or Home). While it lasts, the microphone is released. */
    @Volatile private var userPaused = false

    fun requestStop() {
        stopRequested = true
    }

    fun setUserPaused(paused: Boolean) {
        userPaused = paused
    }

    /**
     * Ambient noises already saved in this session (wind, fan…), so they are not repeated.
     * Used only by the classification thread. If the saved clip was deleted, `addRepeat` returns
     * false and a new one is saved, which becomes the reference clip.
     */
    private val repeats = RepeatFilter()
    private var groupRepeats = true

    /** Sounds the user asked to ignore. Changes mid-night if they mark one from a clip. */
    @Volatile private var ignoredSounds: Set<SoundType> = emptySet()

    fun setIgnoredSounds(types: Set<SoundType>) {
        ignoredSounds = types
    }

    suspend fun run(session: SessionEntity, sensitivity: Sensitivity, groupRepeats: Boolean = true) {
        this.groupRepeats = groupRepeats
        val captureDispatcher = singleThread("Murmur-capture")
        val classifyDispatcher = singleThread("Murmur-classify")
        val capture = openAudio()
        val monitor = InterruptionMonitor(context) { capture.audioSessionId }
        val classifier = runCatching { withContext(classifyDispatcher) { loadClassifier() } }
            .onFailure { Log.e(TAG, "Could not load YAMNet; events will be saved unclassified", it) }
            .getOrNull()
        val aac = session.fullRecordingPath?.let { path ->
            runCatching { AacFullRecorder.open(File(path)) }
                .onFailure { Log.e(TAG, "Could not start the full recording", it) }
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
                    for (op in dbOps) runCatching { op() }.onFailure { Log.e(TAG, "Write failed", it) }
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

                // Stop: drain the classification queue (10 s max) and the pending writes.
                queue.close()
                if (withTimeoutOrNull(DRAIN_TIMEOUT_MS) { worker.join() } == null) {
                    Log.w(TAG, "Classification did not finish in time; dropping pending events")
                    worker.cancelAndJoin() // waits for the in-flight inference to finish before closing YAMNet
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
        // Time anchor of each continuous stretch: time = anchor + samples / 16 (§4.1).
        var anchorMs = System.currentTimeMillis()
        var samplesSinceAnchor = 0L
        var pausedReason: String? = null
        var backoffMs = INITIAL_BACKOFF_MS
        var baselineSaved = false
        var zeroBlocks = 0
        // Identify the background when warm-up ends and again when calibration ends.
        var probesDone = 0

        fun enqueue(event: ClosedEvent?) {
            if (event == null) return
            if (!queue.trySend(ClassifyJob.Event(event)).isSuccess) {
                Log.w(TAG, "Classification queue full: event dropped")
                liveState.update { it.copy(droppedEvents = it.droppedEvents + 1) }
            }
        }

        fun beginPause(reason: String) {
            val at = System.currentTimeMillis()
            enqueue(detector.interrupt())
            // startPause closes the previous pause if the reason changes.
            dbOps.trySend { repository.startPause(sessionId, at, reason) }
            pausedReason = reason
            liveState.update { it.copy(phase = LivePhase.PAUSED, pauseReason = reason, levelDb = Pcm.FLOOR_DB) }
            onStatusChanged(liveState.snapshot.value.totalEvents, reason)
        }

        fun endPause() {
            val at = System.currentTimeMillis()
            dbOps.trySend { repository.endPause(sessionId, at) }
            pausedReason = null
            anchorMs = at
            samplesSinceAnchor = 0
            zeroBlocks = 0
            // No recalibration: the baseline is kept (§6.5).
            val phase = if (detector.baseline.isCalibrated) LivePhase.LISTENING else LivePhase.CALIBRATING
            liveState.update { it.copy(phase = phase, pauseReason = null) }
            onStatusChanged(liveState.snapshot.value.totalEvents, null)
        }

        if (capture.open()) {
            liveState.update { it.copy(audioSource = capture.sourceName) }
        } else {
            beginPause(PauseReason.AUDIO_ERROR)
        }

        while (!stopRequested) {
            currentCoroutineContext().ensureActive()

            // Manual pause: the open event is closed and the microphone is released (Android's green
            // indicator turns off). On resume it is reopened and the same baseline is kept (§6.5).
            if (userPaused) {
                if (pausedReason != PauseReason.USER) beginPause(PauseReason.USER)
                if (capture.isOpen) capture.release()
                delay(STOP_POLL_MS)
                continue
            }
            if (pausedReason == PauseReason.USER) {
                if (capture.open()) {
                    backoffMs = INITIAL_BACKOFF_MS
                    liveState.update { it.copy(audioSource = capture.sourceName) }
                    endPause()
                } else {
                    beginPause(PauseReason.AUDIO_ERROR)
                }
                continue
            }

            if (!capture.isOpen) {
                // Retry with exponential backoff: 1, 2, 4… up to 30 s.
                var waited = 0L
                while (waited < backoffMs && !stopRequested) {
                    delay(STOP_POLL_MS)
                    waited += STOP_POLL_MS
                }
                if (stopRequested) break
                if (capture.open()) {
                    backoffMs = INITIAL_BACKOFF_MS
                    liveState.update { it.copy(audioSource = capture.sourceName) }
                    if (pausedReason == PauseReason.AUDIO_ERROR) endPause()
                } else {
                    backoffMs = minOf(backoffMs * 2, MAX_BACKOFF_MS)
                }
                continue
            }

            val n = capture.readBlock(buffer)
            if (n < 0) {
                Log.w(TAG, "Microphone read error: $n")
                capture.release()
                if (pausedReason != PauseReason.AUDIO_ERROR) beginPause(PauseReason.AUDIO_ERROR)
                continue
            }

            // Sustained digital zeros = another app has the microphone even if the system does not say so.
            val level = Pcm.rmsDb(buffer, 0, n)
            zeroBlocks = if (level <= Pcm.FLOOR_DB) zeroBlocks + 1 else 0
            val reason = monitor.currentReason
                ?: if (zeroBlocks >= ZERO_BLOCKS_FOR_PAUSE) PauseReason.MIC_BUSY else null
            if (reason != null) {
                if (pausedReason != reason) beginPause(reason)
                continue // keep reading to keep the AudioRecord alive, but discard the audio
            }
            if (pausedReason != null) {
                endPause()
                continue // this block belongs to the previous stretch; the new stretch starts now
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
            // Background probe: only while the detector is idle, so an event is not classified as background.
            val probeDue = (probesDone == 0 && out.thresholdDb != null) || (probesDone == 1 && calibrated)
            if (probeDue && out.state == DetectorState.LISTENING && out.closedEvent == null) {
                queue.trySend(ClassifyJob.BackgroundProbe(detector.recentAudio(PROBE_MS)))
                probesDone++
            }
            val phase = when {
                out.state == DetectorState.EVENT -> LivePhase.EVENT
                !calibrated -> LivePhase.CALIBRATING
                else -> LivePhase.LISTENING
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

        // End of session: the open event is classified and saved.
        detector.interrupt()?.let { queue.send(ClassifyJob.Event(it)) }
        if (pausedReason != null) {
            val at = System.currentTimeMillis()
            dbOps.trySend { repository.endPause(sessionId, at) }
        }
    }

    private suspend fun processEvent(sessionId: Long, event: ClosedEvent, analyzer: ClipAnalyzer?) {
        try {
            val result = analyzer?.analyze(event.pcm) ?: unclassified(event)
            val discarded = result.category == Category.BACKGROUND
            // A sound on the ignore list (the dog, the AC…): no WAV and no row.
            val ignoredType = if (discarded) null else IgnoreFilter.ignoredType(result, ignoredSounds)
            val skipped = discarded || ignoredType != null
            // Ambient noise already saved tonight (the fan, the wind…): counted as a repeat of
            // the first one, with no new clip.
            val ambient = if (groupRepeats && !skipped) RepeatFilter.ambientType(result) else null
            val foldInto = repeats.foldInto(ambient, event.startedAtMs)
            val repeated = foldInto != null && repository.addRepeat(sessionId, foldInto, event.startedAtMs)
            // BACKGROUND is discarded: no WAV and no row. If saving fails, it shows on Home (never silently).
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
            Log.e(TAG, "Could not process an event", e)
            liveState.update {
                it.copy(saveErrors = it.saveErrors + 1, lastError = "Error al procesar un sonido: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
            }
        }
    }

    private fun probeBackground(pcm: ShortArray, analyzer: ClipAnalyzer?) {
        if (analyzer == null || pcm.isEmpty()) return
        runCatching { analyzer.analyze(pcm) }
            .onSuccess { r -> liveState.update { it.copy(backgroundLabel = r.meanScores.firstOrNull()?.label) } }
            .onFailure { Log.w(TAG, "Could not identify the background", it) }
    }

    private fun unclassified(event: ClosedEvent) = ClassificationResult(
        category = Category.OTHER,
        confidence = 0f,
        otherLabel = LabelMapper.UNCLASSIFIED_LABEL,
        topLabels = emptyList(),
        meanScores = emptyList(),
        snore = SnoreHeuristic.analyze(event.pcm),
        windowCount = 0,
    )

    private fun singleThread(name: String): ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, name) }.asCoroutineDispatcher()

    private companion object {
        const val TAG = "SessionEngine"
        // Each event takes at most ~2 MB (65 s): 16 queued ≈ 32 MB in the worst case. With 4, events
        // were lost in bursts (plan §12, B7).
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
