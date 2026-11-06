package com.nochesonora.core.detection

import com.nochesonora.core.audio.Pcm
import com.nochesonora.core.audio.RingBuffer

/**
 * Capa 2: máquina de estados que abre y cierra eventos sobre la línea base (plan §4.4).
 *
 * Contiene la [BaselineTracker] y el anillo de pre-roll, así que el llamador solo entrega
 * bloques. Toda la memoria se reserva al construirlo: un búfer de evento de ~63 s y un
 * anillo de 5 s. Cada evento cerrado se entrega como copia recortada.
 */
class EventDetector(private val config: DetectorConfig = DetectorConfig()) {
    val baseline = BaselineTracker(config)

    private val ring = RingBuffer(config.samples(config.ringBufferMs))
    private val preRollSamples = config.samples(config.preRollMs)
    private val postRollSamples = config.samples(config.postRollMs)
    private val pcm = ShortArray(config.samples(config.preRollMs + config.maxEventMs) + 2 * config.blockSamples)
    private var pcmSize = 0

    var state: DetectorState = DetectorState.CALIBRANDO
        private set

    // Onset
    private var onsetStartMs = 0L
    private var onsetCount = 0
    private var onsetSamplesInRing = 0
    private var onsetPeakDb = Pcm.FLOOR_DB

    // Evento abierto
    private var eventStartMs = 0L
    private var clipStartMs = 0L
    private var lastActiveEndMs = 0L
    private var lastActiveEndIdx = 0
    private var belowCount = 0
    private var peakDb = Pcm.FLOOR_DB
    private var activeBlocks = 0
    /** El evento abierto sigue a un corte por duración máxima: hay un nivel sostenido. */
    private var isContinuation = false

    fun onBlock(
        timestampMs: Long,
        samples: ShortArray,
        levelDb: Float = Pcm.rmsDb(samples),
        offset: Int = 0,
        length: Int = samples.size - offset,
    ): DetectorOutput {
        val blockEndMs = timestampMs + length * 1000L / config.sampleRate

        // Calentamiento (primeros 5 s): aún no hay base. Después se detecta aunque siga calibrando.
        val threshold = baseline.thresholdDb
        if (threshold == null) {
            baseline.onBlock(levelDb, track = true)
            ring.write(samples, offset, length)
            state = DetectorState.CALIBRANDO
            return output(levelDb, null)
        }

        val above = levelDb > threshold
        var closed: ClosedEvent? = null

        when (state) {
            DetectorState.CALIBRANDO, DetectorState.ESCUCHANDO -> if (above) {
                onsetStartMs = timestampMs
                onsetCount = 1
                onsetSamplesInRing = 0
                onsetPeakDb = levelDb
                if (onsetCount >= config.onsetBlocks) {
                    startEvent(timestampMs, blockEndMs, samples, offset, length, levelDb)
                } else {
                    state = DetectorState.ONSET
                }
            } else {
                state = DetectorState.ESCUCHANDO
            }

            DetectorState.ONSET -> if (above) {
                onsetCount++
                onsetPeakDb = maxOf(onsetPeakDb, levelDb)
                if (onsetCount >= config.onsetBlocks) {
                    startEvent(timestampMs, blockEndMs, samples, offset, length, levelDb)
                }
            } else {
                state = DetectorState.ESCUCHANDO
            }

            DetectorState.EVENTO -> {
                append(samples, offset, length)
                peakDb = maxOf(peakDb, levelDb)
                if (above) {
                    lastActiveEndMs = blockEndMs
                    lastActiveEndIdx = pcmSize
                    belowCount = 0
                    activeBlocks++
                } else {
                    belowCount++
                }
                if (belowCount >= config.hangoverBlocks) {
                    closed = close(trimToPostRoll = true)
                    state = DetectorState.ESCUCHANDO
                } else if (blockEndMs - eventStartMs >= config.maxEventMs) {
                    if (belowCount == 0) {
                        // Corte a los 60 s: sin post-roll, y el siguiente empieza aquí sin pre-roll (A7).
                        closed = close(trimToPostRoll = false)
                        startContinuation(blockEndMs)
                    } else {
                        closed = close(trimToPostRoll = true)
                        state = DetectorState.ESCUCHANDO
                    }
                }
            }
        }

        val track = state == DetectorState.ESCUCHANDO || (state == DetectorState.EVENTO && isContinuation)
        baseline.onBlock(levelDb, track)
        ring.write(samples, offset, length)
        if (state == DetectorState.ONSET) onsetSamplesInRing += length
        return output(levelDb, closed)
    }

    /**
     * Pausa (micrófono perdido) o fin de sesión: cierra el evento abierto con lo que haya y
     * vacía el anillo, porque el audio siguiente ya no es contiguo.
     */
    /** Últimos [ms] de audio (como mucho los 5 s del anillo), p. ej. para identificar el fondo. */
    fun recentAudio(ms: Long): ShortArray = ring.readLast(config.samples(ms))

    fun interrupt(): ClosedEvent? {
        val closed = if (state == DetectorState.EVENTO) close(trimToPostRoll = true) else null
        state = if (baseline.thresholdDb != null) DetectorState.ESCUCHANDO else DetectorState.CALIBRANDO
        ring.clear()
        pcmSize = 0
        return closed
    }

    private fun startEvent(
        timestampMs: Long,
        blockEndMs: Long,
        samples: ShortArray,
        offset: Int,
        length: Int,
        levelDb: Float,
    ) {
        // Pre-roll: 3 s anteriores al primer bloque del onset, más los bloques del onset ya escritos.
        val pre = ring.readLast(preRollSamples + onsetSamplesInRing)
        System.arraycopy(pre, 0, pcm, 0, pre.size)
        pcmSize = pre.size
        clipStartMs = timestampMs - Pcm.samplesToMs(pre.size.toLong())
        append(samples, offset, length)
        eventStartMs = onsetStartMs
        lastActiveEndMs = blockEndMs
        lastActiveEndIdx = pcmSize
        belowCount = 0
        peakDb = maxOf(onsetPeakDb, levelDb)
        activeBlocks = onsetCount
        isContinuation = false
        state = DetectorState.EVENTO
    }

    private fun startContinuation(atMs: Long) {
        pcmSize = 0
        eventStartMs = atMs
        clipStartMs = atMs
        lastActiveEndMs = atMs
        lastActiveEndIdx = 0
        belowCount = 0
        peakDb = Pcm.FLOOR_DB
        activeBlocks = 0
        isContinuation = true
        state = DetectorState.EVENTO
    }

    private fun append(samples: ShortArray, offset: Int, length: Int) {
        val n = minOf(length, pcm.size - pcmSize)
        System.arraycopy(samples, offset, pcm, pcmSize, n)
        pcmSize += n
    }

    private fun close(trimToPostRoll: Boolean): ClosedEvent? {
        val wasCut = !trimToPostRoll
        val result = if (activeBlocks == 0) {
            null // continuación que no volvió a superar el umbral: no hay nada que guardar
        } else {
            val end = if (trimToPostRoll) minOf(pcmSize, lastActiveEndIdx + postRollSamples) else pcmSize
            ClosedEvent(
                startedAtMs = eventStartMs,
                endedAtMs = lastActiveEndMs,
                clipStartedAtMs = clipStartMs,
                peakDb = peakDb,
                pcm = pcm.copyOf(end),
                wasCut = wasCut,
            )
        }
        pcmSize = 0
        return result
    }

    private fun output(levelDb: Float, closed: ClosedEvent?) = DetectorOutput(
        state = state,
        levelDb = levelDb,
        baselineDb = baseline.baselineDb,
        thresholdDb = baseline.thresholdDb,
        calibrationProgress = baseline.calibrationProgress,
        closedEvent = closed,
    )
}
