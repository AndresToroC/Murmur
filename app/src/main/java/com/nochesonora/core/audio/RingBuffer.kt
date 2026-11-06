package com.nochesonora.core.audio

/** Fixed-capacity ring of samples. Used for the pre-roll of events. */
class RingBuffer(val capacity: Int) {
    private val data = ShortArray(capacity)
    private var writePos = 0

    /** Valid samples (at most [capacity]). */
    var size = 0
        private set

    fun write(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset) {
        var src = offset
        var remaining = length
        // If more arrives than fits, only the last `capacity` samples matter.
        if (remaining > capacity) {
            src += remaining - capacity
            remaining = capacity
        }
        while (remaining > 0) {
            val chunk = minOf(remaining, capacity - writePos)
            System.arraycopy(samples, src, data, writePos, chunk)
            writePos = (writePos + chunk) % capacity
            src += chunk
            remaining -= chunk
        }
        size = minOf(capacity, size + length)
    }

    /** Copies the last [count] samples (or fewer if not that many exist) in chronological order. */
    fun readLast(count: Int): ShortArray {
        val n = minOf(count, size)
        val out = ShortArray(n)
        val start = (writePos - n + capacity) % capacity
        val firstChunk = minOf(n, capacity - start)
        System.arraycopy(data, start, out, 0, firstChunk)
        if (n > firstChunk) System.arraycopy(data, 0, out, firstChunk, n - firstChunk)
        return out
    }

    fun clear() {
        writePos = 0
        size = 0
    }
}
