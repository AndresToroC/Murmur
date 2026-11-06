package com.nochesonora.core.detection

import com.nochesonora.core.audio.Pcm
import com.nochesonora.core.audio.RingBuffer

/**
 * Layer 2: state machine that opens and closes events above the baseline (plan §4.4).
 *
 * Owns the [BaselineTracker] and the pre-roll ring, so the caller only hands in
 * blocks. All memory is allocated at construction: a ~63 s event buffer and a
 * 5 s ring. Each closed event is delivered as a trimmed copy.
 */
class EventDetector(private val config: DetectorConfig = DetectorConfig()) {
    val baseline = BaselineTracker(config)

    private val ring = RingBuffer(config.samples(config.ringBufferMs))
    private val preRollSamples = config.samples(config.preRollMs)
    private val postRollSamples = config.samples(config.postRollMs)
    private val pcm = ShortArray(config.samples(config.preRollMs + config.maxEventMs) + 2 * config.blockSamples)
    private var pcmSize = 0

    var state: DetectorState = DetectorState.CALIBRATING
        private set

    // Onset
    private var onsetStartMs = 0L
    private var onsetCount = 0
    private var onsetSamplesInRing = 0
    private var onsetPeakDb = Pcm.FLOOR_DB

    // Open event
    private var eventStartMs = 0L
    private var clipStartMs = 0L
    private var lastActiveEndMs = 0L
    private var lastActiveEndIdx = 0
    private var belowCount = 0
    private var peakDb = Pcm.FLOOR_DB
    private var activeBlocks = 0
    /** The open event follows a max-duration cut: there is a sustained level. */
    private var isContinuation = false

    fun onBlock(
        timestampMs: Long,
        samples: ShortArray,
        levelDb: Float = Pcm.rmsDb(samples),
        offset: Int = 0,
        length: Int = samples.size - offset,
    ): DetectorOutput {
        val blockEndMs = timestampMs + length * 1000L / config.sampleRate

        // Warm-up (first 5 s): no baseline yet. After that, detection runs even while still calibrating.
        val threshold = baseline.thresholdDb
        if (threshold == null) {
            baseline.onBlock(levelDb, track = true)
            ring.write(samples, offset, length)
            state = DetectorState.CALIBRATING
            return output(levelDb, null)
        }

        val above = levelDb > threshold
        var closed: ClosedEvent? = null

        when (state) {
            DetectorState.CALIBRATING, DetectorState.LISTENING -> if (above) {
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
                state = DetectorState.LISTENING
            }

            DetectorState.ONSET -> if (above) {
                onsetCount++
                onsetPeakDb = maxOf(onsetPeakDb, levelDb)
                if (onsetCount >= config.onsetBlocks) {
                    startEvent(timestampMs, blockEndMs, samples, offset, length, levelDb)
                }
            } else {
                state = DetectorState.LISTENING
            }

            DetectorState.EVENT -> {
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
                    state = DetectorState.LISTENING
                } else if (blockEndMs - eventStartMs >= config.maxEventMs) {
                    if (belowCount == 0) {
                        // Cut at 60 s: no post-roll, and the next one starts here with no pre-roll (A7).
                        closed = close(trimToPostRoll = false)
                        startContinuation(blockEndMs)
                    } else {
                        closed = close(trimToPostRoll = true)
                        state = DetectorState.LISTENING
                    }
                }
            }
        }

        val track = state == DetectorState.LISTENING || (state == DetectorState.EVENT && isContinuation)
        baseline.onBlock(levelDb, track)
        ring.write(samples, offset, length)
        if (state == DetectorState.ONSET) onsetSamplesInRing += length
        return output(levelDb, closed)
    }

    /**
     * Pause (microphone lost) or end of session: closes the open event with whatever it has and
     * empties the ring, because the following audio is no longer contiguous.
     */
    /** Last [ms] of audio (at most the ring's 5 s), e.g. to identify the background. */
    fun recentAudio(ms: Long): ShortArray = ring.readLast(config.samples(ms))

    fun interrupt(): ClosedEvent? {
        val closed = if (state == DetectorState.EVENT) close(trimToPostRoll = true) else null
        state = if (baseline.thresholdDb != null) DetectorState.LISTENING else DetectorState.CALIBRATING
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
        // Pre-roll: the 3 s before the first onset block, plus the onset blocks already written.
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
        state = DetectorState.EVENT
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
        state = DetectorState.EVENT
    }

    private fun append(samples: ShortArray, offset: Int, length: Int) {
        val n = minOf(length, pcm.size - pcmSize)
        System.arraycopy(samples, offset, pcm, pcmSize, n)
        pcmSize += n
    }

    private fun close(trimToPostRoll: Boolean): ClosedEvent? {
        val wasCut = !trimToPostRoll
        val result = if (activeBlocks == 0) {
            null // continuation that never rose above the threshold again: nothing to save
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
