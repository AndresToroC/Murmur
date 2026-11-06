package com.nochesonora.core.audio

/** Anillo de muestras de capacidad fija. Sirve para el pre-roll de los eventos. */
class RingBuffer(val capacity: Int) {
    private val data = ShortArray(capacity)
    private var writePos = 0

    /** Muestras válidas (como mucho [capacity]). */
    var size = 0
        private set

    fun write(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset) {
        var src = offset
        var remaining = length
        // Si llega más de lo que cabe, solo importan las últimas `capacity` muestras.
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

    /** Copia las últimas [count] muestras (o menos si no hay tantas) en orden cronológico. */
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
